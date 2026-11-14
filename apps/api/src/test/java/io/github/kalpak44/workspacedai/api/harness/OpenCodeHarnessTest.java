package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenCodeHarnessTest {

  static final String DIRECTORY = Harness.PROJECT_ROOT;

  FakeOpenCode opencode;
  final OpenCodeHarness harness = new OpenCodeHarness(JsonMapper.builder().build());

  @BeforeEach
  void setUp() throws Exception {
    opencode = new FakeOpenCode();
  }

  @AfterEach
  void tearDown() {
    opencode.close();
  }

  @Test
  void validatesV2AndResolvesTheProjectLocation() {
    harness.validate(opencode.endpoint(), DIRECTORY);
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::path, FakeOpenCode.Request::directory)
        .containsExactly(Tuple.tuple("/api/info", null), Tuple.tuple("/api/location", DIRECTORY));
  }

  @Test
  void readsAnswersAndCancelsPendingQuestions() {
    var form = harness.forms(opencode.endpoint(), FakeOpenCode.SESSION).getFirst();
    assertThat(form.id()).isEqualTo("frm_age");
    assertThat(form.fields().get(0).path("type").asString()).isEqualTo("integer");
    harness.replyForm(opencode.endpoint(), FakeOpenCode.SESSION, form.id(), Map.of("age", 35));
    assertThat(opencode.requests.getLast().body()).isEqualTo("{\"answer\":{\"age\":35}}");
    harness.cancelForm(opencode.endpoint(), FakeOpenCode.SESSION, form.id());
    assertThat(opencode.requests.getLast().method()).isEqualTo("DELETE");
  }

  @Test
  void routesFormEventsToTheirOwningSession() {
    var json = JsonMapper.builder().build();
    for (var type : List.of("form.created", "form.replied", "form.cancelled")) {
      var data =
          type.equals("form.created")
              ? "{\"form\":{\"id\":\"frm_age\",\"sessionID\":\"ses_1\"}}"
              : "{\"id\":\"frm_age\",\"sessionID\":\"ses_1\"}";
      assertThat(
              OpenCodeHarness.events(
                  json.readTree("{\"type\":\"" + type + "\",\"data\":" + data + "}"),
                  new TreeMap<>()))
          .containsExactly(new HarnessEventEnvelope("ses_1", new HarnessEvent.Forms()));
    }
  }

  @Test
  void createsAndListsASessionAtTheProjectLocation() {
    var id = harness.createSession(opencode.endpoint(), DIRECTORY, null);
    assertThat(id).isEqualTo(FakeOpenCode.SESSION);
    assertThat(opencode.requests.getFirst().body())
        .isEqualTo("{\"location\":{\"directory\":\"/home/workspace/project\"}}");
    assertThat(harness.sessions(opencode.endpoint()))
        .singleElement()
        .satisfies(
            session -> {
              assertThat(session.id()).isEqualTo(FakeOpenCode.SESSION);
              assertThat(session.directory()).isEqualTo(DIRECTORY);
              assertThat(session.createdAt()).isNotNull();
              assertThat(session.updatedAt())
                  .isEqualTo(java.time.Instant.ofEpochMilli(opencode.sessionUpdatedAt));
            });
  }

  @Test
  void listsASessionUpdatedBetweenPagesOnceWithItsLatestTitle() {
    opencode.sessionBusy = true;
    assertThat(harness.sessions(opencode.endpoint()))
        .singleElement()
        .satisfies(session -> assertThat(session.title()).isEqualTo("Renamed while listing"));
  }

  @Test
  void deletesASessionAndToleratesOneAlreadyGone() {
    harness.deleteSession(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    harness.deleteSession(opencode.endpoint(), DIRECTORY, "ses_gone");
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .containsExactly(
            Tuple.tuple("DELETE", "/api/session/" + FakeOpenCode.SESSION),
            Tuple.tuple("DELETE", "/api/session/ses_gone"));
  }

  @Test
  void readsProjectedConversationMessages() {
    assertThat(harness.messages(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION))
        .containsExactly(
            new ChatMessage(
                "msg_u",
                "user",
                List.of(new ChatPart("msg_u:text", "text", "List the files", null, null))),
            new ChatMessage(
                "msg_a",
                "assistant",
                List.of(
                    new ChatPart("msg_a:reasoning:0", "reasoning", "I should run ls.", null, null),
                    new ChatPart(
                        "tool_1",
                        "tool",
                        "bash",
                        "bash",
                        "completed",
                        JsonMapper.builder().build().createObjectNode(),
                        ""),
                    new ChatPart("msg_a:text:2", "text", "There are none.", null, null))));
  }

  @Test
  void readsTheSessionTitle() {
    assertThat(harness.title(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION)).isEmpty();
    opencode.title = "Fix the login form";
    assertThat(harness.title(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION))
        .hasValue("Fix the login form");
  }

  @Test
  void switchesTheModelThenPromptsAndInterrupts() {
    harness.prompt(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION, "Hi", null);
    harness.prompt(
        opencode.endpoint(),
        DIRECTORY,
        FakeOpenCode.SESSION,
        "Again",
        new Harness.ModelRef("openrouter", "anthropic/claude-haiku-5.5"));
    harness.abort(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::path)
        .containsExactly(
            "/api/session/" + FakeOpenCode.SESSION + "/prompt",
            "/api/session/" + FakeOpenCode.SESSION + "/model",
            "/api/session/" + FakeOpenCode.SESSION + "/prompt",
            "/api/session/" + FakeOpenCode.SESSION + "/interrupt");
    assertThat(opencode.requests.get(1).body())
        .contains("\"providerID\":\"openrouter\"", "\"id\":\"anthropic/claude-haiku-5.5\"");
  }

  @Test
  void reportsAServiceThatRefusesFailsOrIsNotThere() {
    assertThatThrownBy(() -> harness.createSession(opencode.wrongPassword(), DIRECTORY, null))
        .isInstanceOf(HarnessUnavailableException.class);
    assertThatThrownBy(() -> harness.messages(opencode.endpoint(), DIRECTORY, "broken"))
        .isInstanceOf(HarnessUnavailableException.class)
        .hasMessageContaining("500");
    var nowhere = new HarnessEndpoint(URI.create("http://localhost:1"), "pw");
    assertThatThrownBy(() -> harness.prompt(nowhere, DIRECTORY, "s", "hi", null))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @Test
  void streamsWorkspaceEventsWithTheirSessionIdentity() throws Exception {
    var events = new CopyOnWriteArrayList<HarnessEventEnvelope>();
    var ended = new CountDownLatch(1);
    try (var _ = harness.stream(opencode.endpoint(), events::add, ended::countDown)) {
      assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(events)
        .filteredOn(e -> FakeOpenCode.SESSION.equals(e.sessionId()))
        .extracting(HarnessEventEnvelope::event)
        .contains(
            new HarnessEvent.Refresh(),
            new HarnessEvent.Status(true),
            new HarnessEvent.Status(false),
            new HarnessEvent.Title("Friendly greeting request"),
            new HarnessEvent.Delta("msg_a", "msg_a:text:0", "second answer here"));
    assertThat(events).anyMatch(e -> e.event() instanceof HarnessEvent.Heartbeat);
  }

  @Test
  void carriesAToolsInputThroughProgressToItsOutput() {
    var json = JsonMapper.builder().build();
    var tools = new TreeMap<String, ChatPart>();
    var ids = "\"sessionID\":\"s\",\"assistantMessageID\":\"msg_a\",\"id\":\"t1\"";
    var events =
        List.of(
            "{\"type\":\"session.tool.input.started\",\"data\":{" + ids + ",\"name\":\"shell\"}}",
            "{\"type\":\"session.tool.called\",\"data\":{"
                + ids
                + ",\"input\":{\"command\":\"ls\"}}}",
            "{\"type\":\"session.tool.progress\",\"data\":{" + ids + ",\"metadata\":{}}}",
            "{\"type\":\"session.tool.success\",\"data\":{"
                + ids
                + ",\"content\":[{\"type\":\"text\",\"text\":\"a.txt\"}]}}");
    var parts =
        events.stream()
            .flatMap(e -> OpenCodeHarness.events(json.readTree(e), tools).stream())
            .map(e -> ((HarnessEvent.Part) e.event()).part())
            .toList();
    var input = json.readTree("{\"command\":\"ls\"}");
    assertThat(parts)
        .containsExactly(
            new ChatPart("t1", "tool", "shell", "shell", "pending"),
            new ChatPart("t1", "tool", "shell", "shell", "running", input, null),
            new ChatPart("t1", "tool", "shell", "shell", "running", input, null),
            new ChatPart("t1", "tool", "shell", "shell", "completed", input, "a.txt"));
    assertThat(tools).isEmpty();
  }

  @Test
  void reportsWhyAToolFailed() {
    var json = JsonMapper.builder().build();
    var event =
        json.readTree(
            "{\"type\":\"session.tool.failed\",\"data\":{\"sessionID\":\"s\","
                + "\"assistantMessageID\":\"msg_a\",\"id\":\"t1\",\"error\":{\"message\":\"exit 1\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"no such file\"}]}}");
    assertThat(OpenCodeHarness.events(event, new TreeMap<>()))
        .extracting(e -> ((HarnessEvent.Part) e.event()).part())
        .containsExactly(
            new ChatPart("t1", "tool", null, null, "error", null, "no such file\nexit 1"));
  }

  @Test
  void mapsFailedExecutionsToAnErrorAndIdleStatus() {
    var json = JsonMapper.builder().build();
    var event =
        json.readTree(
            "{\"type\":\"session.execution.failed\",\"data\":{\"sessionID\":\"s\","
                + "\"error\":{\"message\":\"User not found.\"}}}");
    assertThat(OpenCodeHarness.events(event, new TreeMap<>()))
        .extracting(HarnessEventEnvelope::event)
        .containsExactly(new HarnessEvent.Error("User not found."), new HarnessEvent.Status(false));
  }

  @Test
  void stopsReadingOnceClosedWithoutReportingAnEnd() throws Exception {
    opencode.holdStream = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<HarnessEventEnvelope>();
    var ended = new CountDownLatch(1);
    var stream = harness.stream(opencode.endpoint(), events::add, ended::countDown);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (events.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(events)
        .singleElement()
        .extracting(HarnessEventEnvelope::event)
        .isInstanceOf(HarnessEvent.Heartbeat.class);
    stream.close();
    stream.close();
    assertThat(ended.await(500, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  void listsOnlyEnabledToolCapableProviderModels() {
    var catalogue = harness.models(opencode.endpoint(), "openrouter");
    assertThat(catalogue.defaultModel()).isEqualTo("anthropic/claude-sonnet-5.5");
    assertThat(catalogue.models())
        .containsExactly(
            new ModelOption("anthropic/claude-haiku-5.5", "Claude Haiku 5.5", 200000L, 1.0, 5.0),
            new ModelOption(
                "anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", 1000000L, 2.0, 10.0));
  }

  @Test
  void keepsMcpServersInTheGlobalConfigEveryProjectReads() {
    opencode.mcpConfigFile = "{\"$schema\":\"https://opencode.ai/config.json\"}";
    var github =
        new Harness.McpServerConfig.Remote(
            "https://mcp.test/github", Map.of("Authorization", "Bearer {env:GITHUB_TOKEN}"));
    assertThat(harness.configureMcp(opencode.endpoint(), Map.of("github", github))).isTrue();
    assertThat(opencode.configuredMcp().get("github").get("url").asString())
        .isEqualTo("https://mcp.test/github");
    // Left as written: OpenCode fills it in from the pod when it reads the file.
    assertThat(
            opencode.configuredMcp().get("github").get("headers").get("Authorization").asString())
        .isEqualTo("Bearer {env:GITHUB_TOKEN}");
    assertThat(opencode.mcpConfigFile).contains("\"$schema\"");
    assertThat(opencode.reloads).isEqualTo(1);
    assertThat(harness.mcpStatus(opencode.endpoint())).containsEntry("github", "connected");

    harness.removeMcp(opencode.endpoint(), "github");
    assertThat(opencode.configuredMcp().has("github")).isFalse();
    assertThat(opencode.reloads).isEqualTo(2);
    assertThat(opencode.requests).noneMatch(r -> r.method().equals("PUT"));
    assertThat(harness.configureMcp(opencode.endpoint(), Map.of())).isFalse();
  }

  @Test
  void startsTheConfigFileWhenThereIsNoneAndRemovesRuntimeServersItDoesNotHold() {
    var memory =
        new Harness.McpServerConfig.Local(
            List.of("npx", "-y", "@modelcontextprotocol/server-memory"),
            Map.of("API_KEY", "{env:API_KEY}"));
    harness.configureMcp(opencode.endpoint(), Map.of("memory", memory));
    var written = opencode.configuredMcp().get("memory");
    assertThat(written.get("type").asString()).isEqualTo("local");
    assertThat(written.get("command").get(2).asString())
        .isEqualTo("@modelcontextprotocol/server-memory");
    assertThat(written.get("environment").get("API_KEY").asString()).isEqualTo("{env:API_KEY}");

    opencode.globalMcp.set("forma", opencode.configuredMcp().get("memory"));
    harness.removeMcp(opencode.endpoint(), "forma");
    assertThat(opencode.globalMcp).isEmpty();
    assertThat(opencode.configuredMcp().has("memory")).isTrue();
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .contains(Tuple.tuple("DELETE", "/api/experimental/mcp/forma"));
  }

  @Test
  void createsAndRemovesASessionFolderThroughTheShell() {
    var folder = Harness.SESSIONS_ROOT + "/3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11";
    harness.createDirectory(opencode.endpoint(), folder);
    harness.removeDirectory(opencode.endpoint(), folder);
    assertThat(opencode.shellCommands)
        .containsExactly("mkdir -p -- '" + folder + "'", "rm -rf -- '" + folder + "'");
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .contains(Tuple.tuple("DELETE", "/api/debug/location"));

    opencode.shellExit = 1;
    assertThatThrownBy(() -> harness.createDirectory(opencode.endpoint(), folder))
        .isInstanceOf(HarnessUnavailableException.class);
  }

  @Test
  void quotesAFolderAndRefusesToRemoveOneOutsideTheSessions() {
    harness.createDirectory(opencode.endpoint(), "/home/workspace/sessions/it's");
    assertThat(opencode.shellCommands)
        .containsExactly("mkdir -p -- '/home/workspace/sessions/it'\\''s'");
    for (var outside :
        List.of(
            Harness.PROJECT_ROOT,
            Harness.SESSIONS_ROOT,
            Harness.SESSIONS_ROOT + "/a/b",
            Harness.SESSIONS_ROOT + "/../project",
            "sessions/a")) {
      assertThatThrownBy(() -> harness.removeDirectory(opencode.endpoint(), outside))
          .as(outside)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(opencode.shellCommands).hasSize(1);
  }

  @Test
  void neverPrintsThePassword() {
    assertThat(opencode.endpoint().toString()).doesNotContain(FakeOpenCode.PASSWORD + "]");
    assertThat(new HarnessEndpoint(URI.create("http://h:1"), "secret").toString())
        .isEqualTo("HarnessEndpoint[http://h:1]");
  }

  @Test
  void opensTerminalsUnderTheBasesOwnPathSoTheyGoThroughTheTunnel() {
    var tunnel = URI.create("http://hub:8080/api/v1/internal/vms/v/harness");

    assertThat(OpenCodeHarness.websocket(tunnel, "/api/pty/p/connect"))
        .isEqualTo(URI.create("ws://hub:8080/api/v1/internal/vms/v/harness/api/pty/p/connect"));
    assertThat(OpenCodeHarness.websocket(URI.create("https://h:1/"), "/api/pty/p/connect"))
        .isEqualTo(URI.create("wss://h:1/api/pty/p/connect"));
  }
}

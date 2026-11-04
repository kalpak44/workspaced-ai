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
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("/api/info", null),
            org.assertj.core.groups.Tuple.tuple("/api/location", DIRECTORY));
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
            });
  }

  @Test
  void deletesASessionAndToleratesOneAlreadyGone() {
    harness.deleteSession(opencode.endpoint(), DIRECTORY, FakeOpenCode.SESSION);
    harness.deleteSession(opencode.endpoint(), DIRECTORY, "ses_gone");
    assertThat(opencode.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("DELETE", "/api/session/" + FakeOpenCode.SESSION),
            org.assertj.core.groups.Tuple.tuple("DELETE", "/api/session/ses_gone"));
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
                    new ChatPart("tool_1", "tool", "bash", "bash", "completed"),
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
  void AddsListsAndRemovesRuntimeMcpServers() {
    var github =
        new Harness.McpServerConfig(
            "https://mcp.test/github", Map.of("Authorization", "Bearer {env:GITHUB_TOKEN}"));
    assertThat(harness.configureMcp(opencode.endpoint(), Map.of("github", github))).isTrue();
    assertThat(opencode.globalMcp.get("github").get("url").asString())
        .isEqualTo("https://mcp.test/github");
    assertThat(harness.mcpStatus(opencode.endpoint())).containsEntry("github", "connected");
    harness.removeMcp(opencode.endpoint(), "github");
    assertThat(opencode.globalMcp).isEmpty();
  }

  @Test
  void neverPrintsThePassword() {
    assertThat(opencode.endpoint().toString()).doesNotContain(FakeOpenCode.PASSWORD + "]");
    assertThat(new HarnessEndpoint(URI.create("http://h:1"), "secret").toString())
        .isEqualTo("HarnessEndpoint[http://h:1]");
  }
}

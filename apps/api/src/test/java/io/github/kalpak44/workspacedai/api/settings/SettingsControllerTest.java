package io.github.kalpak44.workspacedai.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.session.SessionService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class SettingsControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired SessionService sessions;
  @Autowired ApplicationEventPublisher events;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID workspace;

  private static FakeOpenCode start() {
    try {
      return new FakeOpenCode();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void harness(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    OPENCODE.requests.clear();
    OPENCODE.globalMcp.removeAll();
    OPENCODE.mcpConfigFile = null;
    OPENCODE.mcpStarting = false;
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces
            .create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null)
            .id();
  }

  @Test
  void persistsSessionTtlAndValidatesThePolicy() throws Exception {
    var initial = send("GET", "/session-ttl", null);
    assertThat(initial.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(initial.body(), SessionTtlSettings.class))
        .isEqualTo(new SessionTtlSettings(false, 168));
    var chosen = "{\"sessionTtlEnabled\":true,\"sessionTtlHours\":24}";
    assertThat(send("PUT", "/session-ttl", chosen).statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(send("GET", "/session-ttl", null).body(), SessionTtlSettings.class))
        .isEqualTo(new SessionTtlSettings(true, 24));
    for (var invalid :
        java.util.List.of(
            "{}",
            "{\"sessionTtlEnabled\":null,\"sessionTtlHours\":24}",
            "{\"sessionTtlEnabled\":true,\"sessionTtlHours\":0}",
            "{\"sessionTtlEnabled\":false,\"sessionTtlHours\":999}")) {
      assertThat(send("PUT", "/session-ttl", invalid).statusCode()).isEqualTo(400);
    }
    assertThat(JSON.readValue(send("GET", "/session-ttl", null).body(), SessionTtlSettings.class))
        .isEqualTo(new SessionTtlSettings(true, 24));
    assertThat(
            send("PUT", "/session-ttl", "{\"sessionTtlEnabled\":false,\"sessionTtlHours\":24}")
                .statusCode())
        .isEqualTo(200);
    workspace = UUID.randomUUID();
    assertThat(send("GET", "/session-ttl", null).statusCode()).isEqualTo(404);
    assertThat(send("PUT", "/session-ttl", chosen).statusCode()).isEqualTo(404);
  }

  @Test
  void listsTheAgentModelsAndKeepsTheOneChosen() throws Exception {
    var catalogue = JSON.readTree(send("GET", "/models", null).body());
    assertThat(catalogue.get("defaultModel").asString()).isEqualTo("anthropic/claude-sonnet-5.5");
    assertThat(catalogue.get("models")).hasSize(2);

    var chosen = send("PUT", "/model", "{\"model\":\"anthropic/claude-haiku-5.5\"}");
    assertThat(chosen.statusCode()).isEqualTo(200);
    assertThat(repository.findById(workspace).orElseThrow().getModel())
        .isEqualTo("anthropic/claude-haiku-5.5");

    // Every reply from now on uses it.
    var session = sessions.create(workspace);
    sessions.send(workspace, session.id(), "Hello");
    assertThat(OPENCODE.requests)
        .anySatisfy(
            r ->
                assertThat(r.body())
                    .contains(
                        "\"providerID\":\"openrouter\"", "\"id\":\"anthropic/claude-haiku-5.5\""));

    assertThat(send("PUT", "/model", "{\"model\":null}").statusCode()).isEqualTo(200);
    assertThat(repository.findById(workspace).orElseThrow().getModel()).isNull();
    sessions.send(workspace, session.id(), "Again");
    assertThat(OPENCODE.requests.getLast().body()).doesNotContain("modelID");
  }

  @Test
  void refusesAModelTheProviderDoesNotOfferForAnAgent() throws Exception {
    assertThat(send("PUT", "/model", "{\"model\":\"google/lyria\"}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/model", "{\"model\":\"nope\"}").statusCode()).isEqualTo(400);
    assertThat(repository.findById(workspace).orElseThrow().getModel()).isNull();
  }

  @Test
  void addsAnMcpServerEverySessionUsesAndRemovesIt() throws Exception {
    var added =
        send(
            "POST",
            "/mcp-servers",
            "{\"name\":\"github\",\"url\":\"https://mcp.test/github\","
                + "\"headers\":{\"Authorization\":\"Bearer {env:GITHUB_TOKEN}\"}}");
    assertThat(added.statusCode()).isEqualTo(201);
    var server = JSON.readTree(added.body());
    assertThat(server.get("status").asString()).isEqualTo("connected");
    // Header values never come back: one may be a token typed in by hand.
    assertThat(added.body()).doesNotContain("Bearer");
    assertThat(server.has("headers")).isFalse();
    assertThat(OPENCODE.configuredMcp().get("github").get("url").asString())
        .isEqualTo("https://mcp.test/github");

    assertThat(JSON.readTree(send("GET", "/mcp-servers", null).body())).hasSize(1);
    assertThat(send("DELETE", "/mcp-servers/github", null).statusCode()).isEqualTo(204);
    assertThat(JSON.readTree(send("GET", "/mcp-servers", null).body())).isEmpty();
    assertThat(OPENCODE.configuredMcp().has("github")).isFalse();
    assertThat(send("DELETE", "/mcp-servers/github", null).statusCode()).isEqualTo(404);
  }

  @Test
  void answersPendingForAServerTheReloadHasNotStartedYet() throws Exception {
    OPENCODE.mcpStarting = true;
    var added =
        send(
            "POST",
            "/mcp-servers",
            "{\"name\":\"github\",\"url\":\"https://mcp.test/github\",\"headers\":{}}");
    assertThat(added.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(added.body()).get("status").asString()).isEqualTo("pending");
    assertThat(OPENCODE.configuredMcp().has("github")).isTrue();
  }

  @Test
  void startsALocalServerByCommandWithItsSecrets() throws Exception {
    var added =
        send(
            "POST",
            "/mcp-servers",
            "{\"name\":\"browserbase\",\"command\":\"npx\",\"args\":[\"@browserbasehq/mcp\"],"
                + "\"env\":{\"BROWSERBASE_API_KEY\":\"\",\"GEMINI_API_KEY\":\"{env:GOOGLE_KEY}\"}}");
    assertThat(added.statusCode()).isEqualTo(201);
    var config = OPENCODE.configuredMcp().get("browserbase");
    assertThat(config.get("type").asString()).isEqualTo("local");
    assertThat(JSON.writeValueAsString(config.get("command")))
        .isEqualTo("[\"npx\",\"@browserbasehq/mcp\"]");
    // An empty value stands for the secret of the variable's own name.
    assertThat(config.get("environment").get("BROWSERBASE_API_KEY").asString())
        .isEqualTo("{env:BROWSERBASE_API_KEY}");
    assertThat(config.get("environment").get("GEMINI_API_KEY").asString())
        .isEqualTo("{env:GOOGLE_KEY}");
  }

  @Test
  void refusesAServerThatMixesOrLacksTheTwoForms() throws Exception {
    for (var body :
        new String[] {
          "{\"name\":\"x\"}",
          "{\"name\":\"x\",\"url\":\"https://x.test\",\"command\":\"npx\"}",
          "{\"name\":\"x\",\"url\":\"https://x.test\",\"args\":[\"a\"]}",
          "{\"name\":\"x\",\"command\":\"npx\",\"headers\":{}}",
          "{\"name\":\"x\",\"command\":\"npx\",\"env\":{\"KEY\":\"raw-token\"}}",
          "{\"name\":\"x\",\"command\":\"npx\",\"env\":{\"BAD-NAME\":\"\"}}",
          "{\"name\":\"x\",\"command\":\"two words\"}"
        }) {
      assertThat(send("POST", "/mcp-servers", body).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(OPENCODE.mcpConfigFile).isNull();
  }

  @Test
  void refusesADuplicateOrAnInvalidServer() throws Exception {
    var github = "{\"name\":\"github\",\"url\":\"https://mcp.test/github\",\"headers\":{}}";
    send("POST", "/mcp-servers", github);
    assertThat(send("POST", "/mcp-servers", github).statusCode()).isEqualTo(409);
    assertThat(
            send(
                    "POST",
                    "/mcp-servers",
                    "{\"name\":\"Bad Name\",\"url\":\"https://x.test\",\"headers\":{}}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send("POST", "/mcp-servers", "{\"name\":\"x\",\"url\":\"ftp://x.test\",\"headers\":{}}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/mcp-servers",
                    "{\"name\":\"x\",\"url\":\"https://x.test\",\"headers\":{\"Bad Header\":\"v\"}}")
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void keepsConfigurationInOpenCodeWithoutDatabaseCopies() throws Exception {
    send(
        "POST",
        "/mcp-servers",
        "{\"name\":\"github\",\"url\":\"https://mcp.test/g\",\"headers\":{}}");
    assertThat(OPENCODE.configuredMcp().has("github")).isTrue();
    assertThat(JSON.readTree(send("GET", "/mcp-servers", null).body())).hasSize(1);
  }

  @Test
  void listsAndRemovesServersConfiguredOutsideTheUi() throws Exception {
    // Not in the global config: listed from OpenCode's effective list, removed from its runtime
    // set.
    OPENCODE.globalMcp.set(
        "Local.Tools", JSON.readTree("{\"type\":\"local\",\"command\":[\"tool-server\"]}"));
    var response = send("GET", "/mcp-servers", null);
    assertThat(response.statusCode()).isEqualTo(200);
    var servers = JSON.readTree(response.body());
    assertThat(servers).hasSize(1);
    assertThat(servers.get(0).path("name").asString()).isEqualTo("Local.Tools");
    assertThat(send("DELETE", "/mcp-servers/Local.Tools", null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.globalMcp).isEmpty();
  }

  @Test
  void answersNotFoundForAnUnknownWorkspace() throws Exception {
    workspace = UUID.randomUUID();
    assertThat(send("GET", "/mcp-servers", null).statusCode()).isEqualTo(404);
    assertThat(send("PUT", "/model", "{\"model\":null}").statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsLiteralCredentialsBeforePersistingAnything() throws Exception {
    assertThat(
            send(
                    "POST",
                    "/mcp-servers",
                    "{\"name\":\"unsafe\",\"url\":\"https://mcp.test/g\",\"headers\":{\"Authorization\":\"Bearer raw-token\"}}")
                .statusCode())
        .isEqualTo(400);
    assertThat(OPENCODE.mcpConfigFile).isNull();
  }

  /** Another API instance's change to the same workspace's MCP servers is waited for. */
  @Test
  void waitsForAnotherInstancesChangeToTheMcpServers() throws Exception {
    holdMcpLock();
    var release =
        Thread.ofVirtual()
            .start(
                () -> {
                  java.util.concurrent.locks.LockSupport.parkNanos(300_000_000L);
                  jdbc.update(
                      "update shedlock set lock_until = timezone('utc', now()) where name = ?",
                      SettingsService.mcpLock(workspace));
                });
    var started = System.nanoTime();
    var added = send("POST", "/mcp-servers", "{\"name\":\"docs\",\"url\":\"https://mcp.test\"}");
    release.join();
    assertThat(added.statusCode()).isEqualTo(201);
    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(300_000_000L);
    assertThat(OPENCODE.configuredMcp().has("docs")).isTrue();
  }

  @Test
  void refusesAnMcpChangeWhileAnotherOutlastsTheWait() throws Exception {
    holdMcpLock();
    var refused = send("POST", "/mcp-servers", "{\"name\":\"docs\",\"url\":\"https://mcp.test\"}");
    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(OPENCODE.configuredMcp().has("docs")).isFalse();
    assertThat(send("DELETE", "/mcp-servers/docs", null).statusCode()).isEqualTo(409);
  }

  /** As another API instance would, mid-change: its lease runs an hour from now. */
  private void holdMcpLock() {
    jdbc.update(
        "insert into shedlock (name, lock_until, locked_at, locked_by)"
            + " values (?, timezone('utc', now()) + interval '1 hour', timezone('utc', now()),"
            + " 'another-instance') on conflict (name) do update set"
            + " lock_until = excluded.lock_until, locked_by = excluded.locked_by",
        SettingsService.mcpLock(workspace));
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/workspaces/" + workspace + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

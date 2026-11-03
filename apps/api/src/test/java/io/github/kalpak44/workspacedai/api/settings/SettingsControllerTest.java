package io.github.kalpak44.workspacedai.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.session.SessionService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatusChanged;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
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
@Import(TestDatabase.class)
class SettingsControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired SessionService sessions;
  @Autowired ApplicationEventPublisher events;
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
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace = workspaces.create("Research", ModelProvider.OPENROUTER, "sk-or-1").id();
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
    assertThat(OPENCODE.requests.getLast().body())
        .contains("\"providerID\":\"openrouter\"", "\"modelID\":\"anthropic/claude-haiku-5.5\"");

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
    assertThat(server.get("headers").get(0).asString()).isEqualTo("Authorization");
    assertThat(OPENCODE.globalMcp.get("github").get("url").asString())
        .isEqualTo("https://mcp.test/github");

    assertThat(JSON.readTree(send("GET", "/mcp-servers", null).body())).hasSize(1);
    assertThat(send("DELETE", "/mcp-servers/github", null).statusCode()).isEqualTo(204);
    assertThat(JSON.readTree(send("GET", "/mcp-servers", null).body())).isEmpty();
    assertThat(OPENCODE.globalMcp.get("github").get("enabled").asBoolean()).isFalse();
    assertThat(send("DELETE", "/mcp-servers/github", null).statusCode()).isEqualTo(404);
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
  void givesAReplacedPodItsServersBackOnceItIsReady() throws Exception {
    send(
        "POST",
        "/mcp-servers",
        "{\"name\":\"github\",\"url\":\"https://mcp.test/g\",\"headers\":{}}");
    // The new pod starts from the image's configuration, without them.
    OPENCODE.globalMcp.removeAll();
    events.publishEvent(new WorkspaceStatusChanged(workspace, WorkspaceStatus.INITIALIZING));
    events.publishEvent(new WorkspaceStatusChanged(workspace, WorkspaceStatus.READY));
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!OPENCODE.globalMcp.has("github") && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(OPENCODE.globalMcp.has("github")).isTrue();
  }

  @Test
  void answersNotFoundForAnUnknownWorkspace() throws Exception {
    workspace = UUID.randomUUID();
    assertThat(send("GET", "/mcp-servers", null).statusCode()).isEqualTo(404);
    assertThat(send("PUT", "/model", "{\"model\":null}").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/workspaces/" + workspace + path))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

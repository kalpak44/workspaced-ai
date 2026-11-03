package io.github.kalpak44.workspacedai.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** The API against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class SessionControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceSessionRepository sessions;
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
    OPENCODE.title = "New session - 2026-11-03T15:09:46.948Z";
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace = workspaces.create("Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void startsASessionInItsOwnDirectoryAndListsIt() throws Exception {
    var created = send("POST", "", null);
    assertThat(created.statusCode()).isEqualTo(201);
    var session = JSON.readValue(created.body(), Session.class);
    assertThat(session.path()).isEqualTo("/home/workspace/sessions/" + session.id());
    assertThat(session.title()).isNull();
    assertThat(session.createdAt()).isNotNull();
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/sessions/" + session.id()));
    assertThat(OPENCODE.requests.getFirst().directory()).isEqualTo(session.path());
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(
            e -> assertThat(e.getHarnessSessionId()).isEqualTo(FakeOpenCode.SESSION));

    var second = JSON.readValue(send("POST", "", null).body(), Session.class);
    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed).hasSize(2);
    // What the create answered is what the list reads back.
    assertThat(listed.get(0).get("createdAt").asString()).isEqualTo(session.createdAt().toString());
    assertThat(listed.get(0).get("id").asString()).isEqualTo(session.id().toString());
    assertThat(listed.get(1).get("id").asString()).isEqualTo(second.id().toString());
  }

  @Test
  void picksUpATitleTheAgentGaveWhileNoChatWasOpen() throws Exception {
    var session = create();
    assertThat(JSON.readTree(send("GET", "", null).body()).get(0).get("title").isNull()).isTrue();

    OPENCODE.title = "Fix the login form";
    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed.get(0).get("title").asString()).isEqualTo("Fix the login form");
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(e -> assertThat(e.getTitle()).isEqualTo("Fix the login form"));
  }

  @Test
  void deletesTheSessionFromTheHarnessToo() throws Exception {
    var session = create();
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(204);
    assertThat(sessions.findAll()).isEmpty();
    assertThat(OPENCODE.requests.getLast().method()).isEqualTo("DELETE");
    assertThat(OPENCODE.requests.getLast().directory()).isEqualTo(session.path());
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(404);
  }

  @Test
  void readsTheConversationAndSendsAndStops() throws Exception {
    var session = create();
    var messages = JSON.readTree(send("GET", "/" + session.id() + "/messages", null).body());
    assertThat(messages).hasSize(2);
    assertThat(messages.get(1).get("parts").get(1).get("tool").asString()).isEqualTo("bash");

    var sent = send("POST", "/" + session.id() + "/messages", "{\"text\":\"Hello\"}");
    assertThat(sent.statusCode()).isEqualTo(202);
    assertThat(OPENCODE.requests.getLast().body()).contains("Hello");
    assertThat(send("POST", "/" + session.id() + "/messages", "{\"text\":\"  \"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "/" + session.id() + "/abort", null).statusCode()).isEqualTo(204);
  }

  @Test
  @Timeout(10)
  void streamsTheReplyAndKeepsTheTitleTheAgentGaveIt() throws Exception {
    var session = create();
    var request =
        HttpRequest.newBuilder(url("/" + session.id() + "/events"))
            .header("Accept", "text/event-stream")
            .build();
    var response = http.send(request, BodyHandlers.ofLines());
    assertThat(response.statusCode()).isEqualTo(200);
    var lines = response.body().iterator();
    // The stream opens with a line of its own, before the harness has said anything.
    assertThat(lines.next()).isEqualTo(":connected");
    var names = new ArrayList<String>();
    var data = new ArrayList<String>();
    // The fake harness replays one reply and ends, which ends this stream too.
    lines.forEachRemaining(
        line -> {
          if (line.startsWith("event:")) names.add(line.substring(6));
          if (line.startsWith("data:")) data.add(line.substring(5));
        });
    assertThat(names).contains("message", "part", "delta", "status", "title");
    assertThat(data)
        .contains(
            "{\"messageId\":\"msg_1a1f30ff5001LcL0VbNMntCSUe\","
                + "\"partId\":\"prt_1a1f31492001Vcb1bBKz7fR863\",\"delta\":\"second answer here\"}");
    assertThat(sessions.findById(session.id()))
        .hasValueSatisfying(e -> assertThat(e.getTitle()).isEqualTo("Friendly greeting request"));
  }

  @Test
  void answersNotFoundForAnUnknownWorkspaceOrSession() throws Exception {
    assertThat(send("GET", "/" + UUID.randomUUID() + "/messages", null).statusCode())
        .isEqualTo(404);
    workspace = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "", null).statusCode()).isEqualTo(404);
  }

  @Test
  void goesWithItsWorkspace() throws Exception {
    create();
    workspaces.delete(workspace);
    assertThat(sessions.findAll()).isEmpty();
  }

  private Session create() throws Exception {
    return JSON.readValue(send("POST", "", null).body(), Session.class);
  }

  private URI url(String path) {
    return URI.create(
        "http://localhost:" + port + "/api/v1/workspaces/" + workspace + "/sessions" + path);
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(url(path))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

package io.github.kalpak44.workspacedai.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
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
import org.assertj.core.groups.Tuple;
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
    OPENCODE.title = null;
    OPENCODE.rejectRevert = false;
    OPENCODE.shellCommands.clear();
    OPENCODE.shellExit = 0;
    OPENCODE.whileListingSessions = null;
    OPENCODE.sessionExists = true;
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace = workspaces.create("Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void startsASessionInItsOwnDirectoryAndListsIt() throws Exception {
    var created = send("POST", "", null);
    assertThat(created.statusCode()).isEqualTo(201);
    var session = JSON.readValue(created.body(), Session.class);
    // A folder of its own is a project of its own to OpenCode.
    assertThat(session.path()).isEqualTo("/home/workspace/sessions/" + session.id());
    assertThat(OPENCODE.shellCommands).containsExactly("mkdir -p -- '" + session.path() + "'");
    assertThat(session.title()).isNull();
    assertThat(session.createdAt()).isNotNull();
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/sessions/" + session.id()));
    assertThat(OPENCODE.requests)
        .anySatisfy(r -> assertThat(r.directory()).isEqualTo(session.path()));
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
  void deletesTheSessionFromTheHarnessWithItsFolder() throws Exception {
    var session = create();
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(204);
    assertThat(sessions.findAll()).isEmpty();
    assertThat(OPENCODE.requests)
        .extracting(FakeOpenCode.Request::method, FakeOpenCode.Request::path)
        .contains(Tuple.tuple("DELETE", "/api/session/" + FakeOpenCode.SESSION));
    assertThat(OPENCODE.shellCommands).last().isEqualTo("rm -rf -- '" + session.path() + "'");
    assertThat(send("DELETE", "/" + session.id(), null).statusCode()).isEqualTo(404);
  }

  @Test
  void listsTheSessionsLeftWhenOneIsDeletedMeanwhile() throws Exception {
    var deleted = create();
    var kept = create();
    OPENCODE.sessionExists = false;
    OPENCODE.whileListingSessions = () -> sessions.deleteById(deleted.id());
    var listed = send("GET", "", null);
    assertThat(listed.statusCode()).isEqualTo(200);
    var body = JSON.readTree(listed.body());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("id").asString()).isEqualTo(kept.id().toString());
    assertThat(body.get(0).get("available").asBoolean()).isFalse();
  }

  @Test
  void keepsTheSharedProjectWhenDeletingASessionFromBeforeFolders() throws Exception {
    var id = UUID.randomUUID();
    sessions.saveAndFlush(
        new WorkspaceSessionEntity(id, workspace, Harness.PROJECT_ROOT, FakeOpenCode.SESSION));
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.shellCommands).isEmpty();
  }

  @Test
  void startsNoSessionWhenItsFolderCannotBeMade() throws Exception {
    OPENCODE.shellExit = 1;
    assertThat(send("POST", "", null).statusCode()).isEqualTo(503);
    assertThat(sessions.findAll()).isEmpty();
    assertThat(OPENCODE.requests)
        .noneMatch(r -> r.method().equals("POST") && r.path().equals("/api/session"));
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
  void editsOnlyUserMessagesAndRegeneratesAfterTruncatingHistory() throws Exception {
    var session = create();
    OPENCODE.requests.clear();
    var path = "/" + session.id() + "/messages/";
    assertThat(send("PUT", path + "msg_u", "{\"text\":\"Revised\"}").statusCode()).isEqualTo(202);
    assertThat(OPENCODE.requests)
        .extracting(FakeOpenCode.Request::path)
        .containsSubsequence(
            "/api/session/" + FakeOpenCode.SESSION + "/model",
            "/api/session/" + FakeOpenCode.SESSION + "/revert/stage",
            "/api/session/" + FakeOpenCode.SESSION + "/revert/commit",
            "/api/session/" + FakeOpenCode.SESSION + "/prompt");
    assertThat(OPENCODE.requests)
        .anySatisfy(
            r -> {
              assertThat(r.path()).endsWith("/revert/stage");
              assertThat(r.body()).contains("\"messageID\":\"msg_u\"", "\"files\":false");
            });
    assertThat(OPENCODE.requests.getLast().body()).contains("Revised");
    OPENCODE.requests.clear();
    assertThat(send("PUT", path + "msg_a", "{\"text\":\"No\"}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", path + "missing", "{\"text\":\"No\"}").statusCode()).isEqualTo(404);
    assertThat(send("PUT", path + "msg_u", "{\"text\":\" \"}").statusCode()).isEqualTo(400);
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).contains("revert"));
  }

  @Test
  void doesNotCommitOrPromptWhenTheHarnessRefusesAnEdit() throws Exception {
    var session = create();
    OPENCODE.rejectRevert = true;
    OPENCODE.requests.clear();
    assertThat(
            send("PUT", "/" + session.id() + "/messages/msg_u", "{\"text\":\"Revised\"}")
                .statusCode())
        .isEqualTo(503);
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).endsWith("/revert/commit"));
    assertThat(OPENCODE.requests).noneSatisfy(r -> assertThat(r.path()).endsWith("/prompt"));
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
            "{\"messageId\":\"msg_a\","
                + "\"partId\":\"msg_a:text:0\",\"delta\":\"second answer here\"}");
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

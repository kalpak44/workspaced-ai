package io.github.kalpak44.workspacedai.api.webserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class WebServerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String REQUEST = Harness.WEB_DIRECTORY + "/request";
  private static final String RESULT = Harness.WEB_DIRECTORY + "/result";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WebServerRequests requests;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoSpyBean WorkspaceRuntime runtime;

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
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
    registry.add("workspaced.web-server.domain", () -> "workspaced.test");
    // Each test runs a pass itself, so the scheduled one never races it.
    registry.add("workspaced.web-server.request-interval", () -> "PT1H");
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    OPENCODE.shellCommands.clear();
    OPENCODE.shellExit = 0;
    OPENCODE.files.clear();
    reset(runtime);
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace = create("Research");
  }

  @Test
  void isOffUntilTurnedOnAndNamesTheDomain() throws Exception {
    var body = JSON.readTree(send("GET", workspace, null).body());
    assertThat(body.get("domain").asString()).isEqualTo("workspaced.test");
    assertThat(body.get("name").isNull()).isTrue();
    assertThat(body.get("url").isNull()).isTrue();
  }

  @Test
  void startsNginxAndRoutesTheNameToIt() throws Exception {
    var response = send("PUT", workspace, "{\"name\":\"my-app\"}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("url").asString())
        .isEqualTo("https://box-my-app.workspaced.test/");
    assertThat(OPENCODE.shellCommands)
        .contains("web-server enable 'https://box-my-app.workspaced.test/'");
    verify(runtime).publish(workspace, "box-my-app");
    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").asString())
        .isEqualTo("my-app");
  }

  @Test
  void givesTheOldNameUpOnRename() throws Exception {
    send("PUT", workspace, "{\"name\":\"first\"}");
    assertThat(send("PUT", workspace, "{\"name\":\"second\"}").statusCode()).isEqualTo(200);

    verify(runtime).publish(workspace, "box-second");
    var other = create("Other");
    assertThat(send("PUT", other, "{\"name\":\"first\"}").statusCode()).isEqualTo(200);
  }

  @Test
  void refusesANameAnotherWorkspaceHoldsOrTheDomainServes() throws Exception {
    send("PUT", workspace, "{\"name\":\"my-app\"}");
    var other = create("Other");

    for (var name : List.of("my-app", "off")) {
      assertThat(send("PUT", other, "{\"name\":\"" + name + "\"}").statusCode())
          .as(name)
          .isEqualTo(409);
    }
    verify(runtime, times(1)).publish(any(), any());
    assertThat(JSON.readTree(send("GET", other, null).body()).get("name").isNull()).isTrue();
  }

  @Test
  void refusesWhatIsNotOneDnsLabel() throws Exception {
    for (var name : List.of("My-App", "-app", "app-", "a.b", "a_b", "", "x".repeat(60), "café")) {
      assertThat(send("PUT", workspace, "{\"name\":\"" + name + "\"}").statusCode())
          .as(name)
          .isEqualTo(400);
    }
    assertThat(send("PUT", workspace, "{}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", workspace, "{\"name\":\"" + "x".repeat(59) + "\"}").statusCode())
        .isEqualTo(200);
    assertThat(OPENCODE.shellCommands).hasSize(2);
  }

  @Test
  void asksForARestartOnAnOlderHarnessAndKeepsNothing() throws Exception {
    OPENCODE.shellExit = 1;

    assertThat(send("PUT", workspace, "{\"name\":\"my-app\"}").statusCode()).isEqualTo(409);

    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").isNull()).isTrue();
    verify(runtime, never()).publish(any(), any());
  }

  @Test
  void putsThePreviousNameBackAndStopsNginxWhenTheRouteFails() throws Exception {
    doThrow(new IllegalStateException("no route")).when(runtime).publish(any(), any());

    assertThat(send("PUT", workspace, "{\"name\":\"my-app\"}").statusCode()).isEqualTo(500);

    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").isNull()).isTrue();
    assertThat(OPENCODE.shellCommands.getLast()).contains("web-server disable");
    var other = create("Other");
    reset(runtime);
    assertThat(send("PUT", other, "{\"name\":\"my-app\"}").statusCode()).isEqualTo(200);
  }

  @Test
  void turnsOffAndFreesTheName() throws Exception {
    send("PUT", workspace, "{\"name\":\"my-app\"}");

    assertThat(send("DELETE", workspace, null).statusCode()).isEqualTo(204);

    assertThat(OPENCODE.shellCommands.getLast()).contains("web-server disable");
    verify(runtime).unpublish(workspace);
    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").isNull()).isTrue();
    assertThat(send("PUT", create("Other"), "{\"name\":\"my-app\"}").statusCode()).isEqualTo(200);
    assertThat(send("DELETE", workspace, null).statusCode()).isEqualTo(204);
  }

  @Test
  void freesTheNameWithTheWorkspace() throws Exception {
    send("PUT", workspace, "{\"name\":\"my-app\"}");
    workspaces.delete(workspace);
    assertThat(send("PUT", create("Other"), "{\"name\":\"my-app\"}").statusCode()).isEqualTo(200);
  }

  @Test
  void answersNotFoundForAnUnknownWorkspace() throws Exception {
    assertThat(send("GET", UUID.randomUUID(), null).statusCode()).isEqualTo(404);
  }

  @Test
  void carriesOutWhatTheAgentAskedOnceTheUserAllowsIt() throws Exception {
    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("agentAllowed").asBoolean())
        .isFalse();
    OPENCODE.files.put(REQUEST, "bot-app\n");
    requests.run();
    // Never read while the user has not allowed it.
    assertThat(OPENCODE.files).doesNotContainKey(RESULT);
    verify(runtime, never()).publish(any(), any());

    var allowed = send("PUT", workspace, "/agent", "{\"allowed\":true}");
    assertThat(JSON.readTree(allowed.body()).get("agentAllowed").asBoolean()).isTrue();
    requests.run();

    assertThat(OPENCODE.files.get(RESULT)).isEqualTo("on https://box-bot-app.workspaced.test/\n");
    assertThat(OPENCODE.shellCommands).contains("rm -f -- '" + REQUEST + "'");
    verify(runtime).publish(workspace, "box-bot-app");
    // The user sees what the agent did.
    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").asString())
        .isEqualTo("bot-app");

    OPENCODE.files.put(REQUEST, "off");
    requests.run();
    assertThat(OPENCODE.files.get(RESULT)).isEqualTo("off\n");
    verify(runtime).unpublish(workspace);
    assertThat(JSON.readTree(send("GET", workspace, null).body()).get("name").isNull()).isTrue();
  }

  @Test
  void answersARefusalForTheAgentToRead() throws Exception {
    send("PUT", workspace, "/agent", "{\"allowed\":true}");
    send("PUT", create("Other"), "{\"name\":\"taken\"}");
    reset(runtime);

    for (var request : List.of("Bad_Name", "taken")) {
      OPENCODE.files.put(REQUEST, request);
      requests.run();
      assertThat(OPENCODE.files.get(RESULT)).as(request).startsWith("error: ");
    }
    assertThat(OPENCODE.files.get(RESULT)).isEqualTo("error: the name taken is taken\n");
    verify(runtime, never()).publish(any(), any());
  }

  @Test
  void leavesARequestAloneWhileTheWorkspaceIsStopped() throws Exception {
    send("PUT", workspace, "/agent", "{\"allowed\":true}");
    doReturn(Optional.empty()).when(runtime).harness(workspace);
    OPENCODE.files.put(REQUEST, "bot-app");
    requests.run();
    assertThat(OPENCODE.files).doesNotContainKey(RESULT);
  }

  @Test
  void savesTheSwitchWhileTheWorkspaceIsStoppedAndRefusesNull() throws Exception {
    doReturn(Optional.empty()).when(runtime).harness(workspace);
    assertThat(send("PUT", workspace, "/agent", "{\"allowed\":true}").statusCode()).isEqualTo(200);
    assertThat(send("PUT", workspace, "/agent", "{}").statusCode()).isEqualTo(400);
  }

  private UUID create(String name) {
    return workspaces.create(TestUsers.ADMIN, name, ModelProvider.OPENROUTER, "sk-or-1", null).id();
  }

  private HttpResponse<String> send(String method, UUID id, String json) throws Exception {
    return send(method, id, "", json);
  }

  private HttpResponse<String> send(String method, UUID id, String path, String json)
      throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/workspaces/" + id + "/web-server" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

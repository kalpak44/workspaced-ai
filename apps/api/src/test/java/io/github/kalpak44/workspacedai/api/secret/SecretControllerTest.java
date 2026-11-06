package io.github.kalpak44.workspacedai.api.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class SecretControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
  @Autowired WorkspaceSecretRepository secrets;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();
  private UUID id;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    id = workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void listsTheProviderKeyFirstThenWhatWasAdded() throws Exception {
    assertThat(list()).containsExactly(new Secret("OPENROUTER_API_KEY", true));

    var added = send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_1\"}");
    assertThat(added.statusCode()).isEqualTo(201);
    assertThat(added.body()).doesNotContain("ghp_1");
    assertThat(added.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/secrets/GITHUB_TOKEN"));
    send("POST", "", "{\"name\":\"NPM_TOKEN\",\"value\":\"npm_1\"}");

    assertThat(list())
        .containsExactly(
            new Secret("OPENROUTER_API_KEY", true),
            new Secret("GITHUB_TOKEN", false),
            new Secret("NPM_TOKEN", false));
    // The provider key is never a row: it lives in the runtime alone.
    assertThat(secrets.findAll())
        .extracting(WorkspaceSecretEntity::getName)
        .containsExactlyInAnyOrder("GITHUB_TOKEN", "NPM_TOKEN");
  }

  @Test
  void marksTheWorkspaceForRestartUntilItRestarts() throws Exception {
    assertThat(pendingRestart()).isFalse();
    send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_1\"}");
    assertThat(pendingRestart()).isTrue();
    workspaces.restart(id);
    assertThat(pendingRestart()).isFalse();
    send("DELETE", "/GITHUB_TOKEN", null);
    assertThat(pendingRestart()).isTrue();
  }

  @Test
  void leavesNothingPendingWhenAChangeIsRefused() throws Exception {
    assertThat(send("POST", "", "{\"name\":\"OPENROUTER_API_KEY\",\"value\":\"x\"}").statusCode())
        .isEqualTo(409);
    assertThat(pendingRestart()).isFalse();
  }

  private boolean pendingRestart() {
    return workspaces.list(TestUsers.ADMIN).getFirst().restartRequired();
  }

  @Test
  void removes() throws Exception {
    send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_1\"}");
    assertThat(send("DELETE", "/GITHUB_TOKEN", null).statusCode()).isEqualTo(204);
    assertThat(list()).containsExactly(new Secret("OPENROUTER_API_KEY", true));
    assertThat(send("DELETE", "/GITHUB_TOKEN", null).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesToShadowOrRemoveWhatTheProviderSets() throws Exception {
    assertThat(send("POST", "", "{\"name\":\"OPENROUTER_API_KEY\",\"value\":\"x\"}").statusCode())
        .isEqualTo(409);
    assertThat(send("POST", "", "{\"name\":\"OPENROUTER_BASE_URL\",\"value\":\"x\"}").statusCode())
        .isEqualTo(409);
    assertThat(send("DELETE", "/OPENROUTER_API_KEY", null).statusCode()).isEqualTo(409);
  }

  @Test
  void refusesToShadowTheHarnessPassword() throws Exception {
    var added = send("POST", "", "{\"name\":\"OPENCODE_SERVER_PASSWORD\",\"value\":\"x\"}");
    assertThat(added.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(added.body()).get("detail").asString())
        .isEqualTo("OPENCODE_SERVER_PASSWORD is set by the workspace's harness");
    assertThat(send("DELETE", "/OPENCODE_SERVER_USERNAME", null).statusCode()).isEqualTo(409);
  }

  @Test
  void refusesADuplicate() throws Exception {
    send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_1\"}");
    assertThat(send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_2\"}").statusCode())
        .isEqualTo(409);
  }

  @Test
  void rejectsANameThatIsNotAnEnvironmentVariableOrAnEmptyValue() throws Exception {
    assertThat(send("POST", "", "{\"name\":\"1TOKEN\",\"value\":\"x\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", "{\"name\":\"MY-TOKEN\",\"value\":\"x\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", "{\"name\":\"TOKEN\",\"value\":\"\"}").statusCode()).isEqualTo(400);
  }

  @Test
  void answersNotFoundForAnUnknownWorkspace() throws Exception {
    id = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "", "{\"name\":\"TOKEN\",\"value\":\"x\"}").statusCode())
        .isEqualTo(404);
    assertThat(send("DELETE", "/TOKEN", null).statusCode()).isEqualTo(404);
  }

  @Test
  void goesWithItsWorkspace() throws Exception {
    send("POST", "", "{\"name\":\"GITHUB_TOKEN\",\"value\":\"ghp_1\"}");
    workspaces.delete(id);
    assertThat(secrets.findAll()).isEmpty();
  }

  private List<Secret> list() throws Exception {
    return JSON.readValue(send("GET", "", null).body(), new TypeReference<>() {});
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/workspaces/" + id + "/secrets" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

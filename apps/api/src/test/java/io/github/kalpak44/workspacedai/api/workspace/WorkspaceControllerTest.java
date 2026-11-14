package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.provider.ProviderUnreachableException;
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
@Import({TestDatabase.class, TestRuntime.class})
class WorkspaceControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired WorkspaceRepository repository;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void emptyTable() {
    repository.deleteAll();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void createsAndListsInCreationOrder() throws Exception {
    var created = send("POST", "", body("  Research  ", "openrouter", " sk-or-1\\n"));
    assertThat(created.statusCode()).isEqualTo(201);
    var research = JSON.readValue(created.body(), Workspace.class);
    assertThat(research.name()).isEqualTo("Research");
    assertThat(research.modelProvider()).isEqualTo("openrouter");
    // The key is checked as it would be used, without the whitespace a paste brings along.
    verify(keyCheck).accepts(ModelProvider.OPENROUTER, "sk-or-1");
    // Outside the cluster nothing is started, so a workspace is usable at once.
    assertThat(research.status()).isEqualTo(WorkspaceStatus.READY);
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/api/v1/workspaces/" + research.id()));

    create("Docs");

    assertThat(list()).extracting(Workspace::name).containsExactly("Research", "Docs");
  }

  @Test
  void createsNothingWithAKeyTheProviderRejects() throws Exception {
    when(keyCheck.accepts(ModelProvider.OPENROUTER, "sk-bad")).thenReturn(false);
    var refused = send("POST", "", body("Research", "openrouter", "sk-bad"));
    assertThat(refused.statusCode()).isEqualTo(422);
    assertThat(JSON.readTree(refused.body()).get("detail").asString())
        .isEqualTo("OpenRouter rejected the API key");
    assertThat(list()).isEmpty();
  }

  @Test
  void createsNothingWhenTheProviderCannotBeReached() throws Exception {
    when(keyCheck.accepts(eq(ModelProvider.OPENROUTER), any()))
        .thenThrow(new ProviderUnreachableException(ModelProvider.OPENROUTER, null));
    assertThat(send("POST", "", body("Research", "openrouter", "sk-or-1")).statusCode())
        .isEqualTo(502);
    assertThat(list()).isEmpty();
  }

  @Test
  void rejectsAnUnknownOrMissingProvider() throws Exception {
    assertThat(send("POST", "", body("Research", "nope", "sk-or-1")).statusCode()).isEqualTo(400);
    assertThat(send("POST", "", "{\"name\":\"Research\"}").statusCode()).isEqualTo(400);
    assertThat(list()).isEmpty();
  }

  @Test
  void createsNothingWithoutAKey() throws Exception {
    var blank = send("POST", "", body("Blank", "openrouter", "  "));
    var none = send("POST", "", "{\"name\":\"None\",\"modelProvider\":\"openrouter\"}");

    assertThat(blank.statusCode()).isEqualTo(400);
    assertThat(none.statusCode()).isEqualTo(400);
    verify(keyCheck, never()).accepts(any(), any());
    assertThat(list()).isEmpty();
  }

  @Test
  void listsTheSupportedModelProviders() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/model-providers"))
            .header("Authorization", TestUsers.bearer(port))
            .build();
    var providers = JSON.readTree(http.send(request, BodyHandlers.ofString()).body());
    assertThat(providers).hasSize(1);
    assertThat(providers.get(0).get("id").asString()).isEqualTo("openrouter");
    assertThat(providers.get(0).get("baseUrl").asString())
        .isEqualTo("https://openrouter.ai/api/v1");
    assertThat(providers.get(0).get("apiKeyVariable").asString()).isEqualTo("OPENROUTER_API_KEY");
  }

  @Test
  void renames() throws Exception {
    var id = create("Old");
    var renamed = send("PUT", "/" + id, "{\"name\":\"New\"}");
    assertThat(renamed.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(renamed.body(), Workspace.class))
        .isEqualTo(
            new Workspace(id, "New", "openrouter", null, null, WorkspaceStatus.READY, false));
    assertThat(list()).extracting(Workspace::name).containsExactly("New");
  }

  @Test
  void deletes() throws Exception {
    var id = create("Gone");
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(list()).isEmpty();
  }

  @Test
  void restarts() throws Exception {
    var id = create("Running");
    assertThat(send("POST", "/" + id + "/restart", null).statusCode()).isEqualTo(202);
    assertThat(send("POST", "/" + UUID.randomUUID() + "/restart", null).statusCode())
        .isEqualTo(404);
  }

  @Test
  void answersNotFoundForAnUnknownIdAsAProblemDetail() throws Exception {
    var id = UUID.randomUUID();
    var renamed = send("PUT", "/" + id, "{\"name\":\"x\"}");
    assertThat(renamed.statusCode()).isEqualTo(404);
    assertThat(renamed.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    var problem = JSON.readTree(renamed.body());
    assertThat(problem.get("status").asInt()).isEqualTo(404);
    assertThat(problem.get("detail").asString()).isEqualTo("no workspace " + id);
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsABlankOrOverlongName() throws Exception {
    assertThat(send("POST", "", body("   ", "openrouter", "k")).statusCode()).isEqualTo(400);
    assertThat(send("POST", "", body("x".repeat(101), "openrouter", "k")).statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", "{}").statusCode()).isEqualTo(400);
    assertThat(send("PUT", "/" + create("Kept"), "{\"name\":\"   \"}").statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsAnIdThatIsNotAUuid() throws Exception {
    assertThat(send("DELETE", "/not-a-uuid", null).statusCode()).isEqualTo(400);
  }

  private UUID create(String name) throws Exception {
    var response = send("POST", "", body(name, "openrouter", "sk-or-1"));
    return JSON.readValue(response.body(), Workspace.class).id();
  }

  private static String body(String name, String provider, String apiKey) {
    return "{\"name\":\""
        + name
        + "\",\"modelProvider\":\""
        + provider
        + "\",\"apiKey\":\""
        + apiKey
        + "\"}";
  }

  private List<Workspace> list() throws Exception {
    return JSON.readValue(send("GET", "", null).body(), new TypeReference<>() {});
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces" + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

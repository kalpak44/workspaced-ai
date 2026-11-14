package io.github.kalpak44.workspacedai.api.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
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
import tools.jackson.databind.json.JsonMapper;

/** Against a harness that answers as opencode does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class RecipeDraftsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String DIRECTORY = Harness.RECIPES_DIRECTORY + "/";

  @LocalServerPort int port;
  @Autowired WorkspaceService workspaces;
  @Autowired WorkspaceRepository repository;
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
    OPENCODE.files.clear();
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces
            .create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1", null)
            .id();
  }

  @Test
  void listsTheDraftsTheAgentWrote() throws Exception {
    OPENCODE.files.put(DIRECTORY + "wordpress.md", "---\ntitle: WordPress\n---\n1. Install.");
    OPENCODE.files.put(DIRECTORY + "backup.md", "---\ntitle: Backup\n---\n1. Copy.");
    OPENCODE.files.put(DIRECTORY + "notes.txt", "not a draft");

    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0).get("name").asString()).isEqualTo("backup");
    assertThat(listed.get(1).get("name").asString()).isEqualTo("wordpress");
    assertThat(listed.get(1).get("content").asString()).startsWith("---\ntitle: WordPress");
  }

  @Test
  void createsReplacesAndDeletesDrafts() throws Exception {
    var created = send("PUT", "/wordpress", "{\"content\":\"1. Install.\"}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(OPENCODE.files).containsEntry(DIRECTORY + "wordpress.md", "1. Install.");

    send("PUT", "/wordpress", "{\"content\":\"1. Install it.\"}");
    assertThat(OPENCODE.files).containsEntry(DIRECTORY + "wordpress.md", "1. Install it.");

    assertThat(send("DELETE", "/wordpress", null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.files).doesNotContainKey(DIRECTORY + "wordpress.md");
    assertThat(send("DELETE", "/wordpress", null).statusCode()).isEqualTo(404);
  }

  @Test
  void refusesNamesThatAreNotPlainFileNamesAndOversizedContent() throws Exception {
    for (var name : new String[] {"..%2Fsecrets", "-rf", "a.b", "x".repeat(64)}) {
      assertThat(send("PUT", "/" + name, "{\"content\":\"x\"}").statusCode())
          .as(name)
          .isEqualTo(400);
    }
    assertThat(send("PUT", "/ok", "{\"content\":\"" + "x".repeat(100_001) + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", "/ok", "{}").statusCode()).isEqualTo(400);
    assertThat(OPENCODE.files.keySet()).allMatch(f -> !f.endsWith("ok.md"));
  }

  @Test
  void answersNotFoundForSomeoneElsesOrAnUnknownWorkspace() throws Exception {
    workspace = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
    assertThat(send("PUT", "/x", "{\"content\":\"x\"}").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/workspaces/"
                        + workspace
                        + "/recipe-drafts"
                        + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

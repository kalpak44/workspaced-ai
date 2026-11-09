package io.github.kalpak44.workspacedai.api.instructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
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
@Import(TestDatabase.class)
class InstructionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  private static final String DIRECTORY = Harness.INSTRUCTIONS_DIRECTORY + "/";

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
    // As start.sh seeds a new workspace from the image's defaults.
    OPENCODE.files.put(DIRECTORY + "working-style.md", "- Keep answers short.");
    // The read-only guide sits elsewhere and is never listed.
    OPENCODE.files.put("/usr/share/harness/instructions/system.md", "# How this workspace works");
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    workspace =
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void listsTheWorkspacesInstructionFilesWithTheirContent() throws Exception {
    // One the agent wrote itself, and a file that is not Markdown, which is not an instruction.
    OPENCODE.files.put(DIRECTORY + "code-review.md", "- Point out missing tests.");
    OPENCODE.files.put(DIRECTORY + "notes.txt", "not an instruction");

    var listed = JSON.readTree(send("GET", "", null).body());
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0).get("name").asString()).isEqualTo("code-review");
    assertThat(listed.get(0).get("content").asString()).isEqualTo("- Point out missing tests.");
    assertThat(listed.get(1).get("name").asString()).isEqualTo("working-style");
  }

  @Test
  void createsReplacesAndDeletesInstructionFiles() throws Exception {
    var reloads = OPENCODE.reloads;
    var created = send("PUT", "/research-rules", "{\"content\":\"- Cite sources.\"}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(OPENCODE.files).containsEntry(DIRECTORY + "research-rules.md", "- Cite sources.");

    send("PUT", "/research-rules", "{\"content\":\"- Cite every source.\"}");
    assertThat(OPENCODE.files)
        .containsEntry(DIRECTORY + "research-rules.md", "- Cite every source.");

    assertThat(send("DELETE", "/research-rules", null).statusCode()).isEqualTo(204);
    assertThat(OPENCODE.files).doesNotContainKey(DIRECTORY + "research-rules.md");
    assertThat(send("DELETE", "/research-rules", null).statusCode()).isEqualTo(404);
    // OpenCode reads instructions when it loads a project, so each change reloads; a missing file
    // changes nothing.
    assertThat(OPENCODE.reloads).isEqualTo(reloads + 3);
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
                        + "/instructions"
                        + path))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

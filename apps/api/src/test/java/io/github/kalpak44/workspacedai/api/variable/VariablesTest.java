package io.github.kalpak44.workspacedai.api.variable;

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
import java.util.List;
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
class VariablesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();

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
        workspaces.create(TestUsers.ADMIN, "Research", ModelProvider.OPENROUTER, "sk-or-1").id();
  }

  @Test
  void listsNothingBeforeThereIsAFile() throws Exception {
    assertThat(JSON.readTree(send("GET", null).body())).isEmpty();
  }

  @Test
  void readsTheFileAsTheHarnessDoes() throws Exception {
    OPENCODE.files.put(
        Harness.VARIABLES_FILE,
        "# written by the agent\nexport PORT=8080\nGREETING=\"hello world\"\n\nPORT=9090\n"
            + "OPENCODE_SERVER_USERNAME=x\nnot a variable\n");
    var listed = JSON.readTree(send("GET", null).body());
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0).get("name").asString()).isEqualTo("PORT");
    assertThat(listed.get(0).get("value").asString()).isEqualTo("8080");
    assertThat(listed.get(1).get("value").asString()).isEqualTo("hello world");
  }

  @Test
  void replacesTheFileWholeAndReadsItBackTheSame() throws Exception {
    var body =
        "[{\"name\":\"LOG_LEVEL\",\"value\":\"info\"},"
            + "{\"name\":\"QUOTED\",\"value\":\"\\\"already quoted\\\"\"},"
            + "{\"name\":\"EMPTY\",\"value\":\"\"}]";
    assertThat(send("PUT", body).statusCode()).isEqualTo(200);
    assertThat(OPENCODE.files.get(Harness.VARIABLES_FILE))
        .isEqualTo("LOG_LEVEL=\"info\"\nQUOTED=\"\"already quoted\"\"\nEMPTY=\"\"\n");
    var listed = JSON.readTree(send("GET", null).body());
    assertThat(listed.get(1).get("value").asString()).isEqualTo("\"already quoted\"");
    assertThat(listed.get(2).get("value").asString()).isEmpty();

    assertThat(send("PUT", "[]").statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(send("GET", null).body())).isEmpty();
  }

  @Test
  void refusesWhatTheHarnessCouldNotSet() throws Exception {
    for (var body :
        List.of(
            "[{\"name\":\"1BAD\",\"value\":\"x\"}]",
            "[{\"name\":\"A-B\",\"value\":\"x\"}]",
            "[{\"name\":\"OPENCODE_SERVER_USERNAME\",\"value\":\"x\"}]",
            "[{\"name\":\"A\",\"value\":\"two\\nlines\"}]",
            "[{\"name\":\"A\",\"value\":\"x\"},{\"name\":\"A\",\"value\":\"y\"}]",
            "[{\"name\":\"A\",\"value\":\"" + "x".repeat(4097) + "\"}]",
            "[{\"name\":\"A\"}]")) {
      assertThat(send("PUT", body).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(OPENCODE.files).doesNotContainKey(Harness.VARIABLES_FILE);
  }

  @Test
  void answersNotFoundForSomeoneElsesOrAnUnknownWorkspace() throws Exception {
    workspace = UUID.randomUUID();
    assertThat(send("GET", null).statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> send(String method, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/workspaces/" + workspace + "/variables"))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

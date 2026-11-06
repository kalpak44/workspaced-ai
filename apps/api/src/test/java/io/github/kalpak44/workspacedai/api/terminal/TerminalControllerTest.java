package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.harness.FakeOpenCode;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real HTTP controller, service, database, and OpenCode client together. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class TerminalControllerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeOpenCode OPENCODE = start();
  @LocalServerPort int port;
  @Autowired WorkspaceRepository workspaces;
  UUID workspace;
  final HttpClient http = HttpClient.newHttpClient();

  static FakeOpenCode start() {
    try {
      return new FakeOpenCode();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.workspace.local-harness.url", () -> OPENCODE.endpoint().baseUrl());
    registry.add("workspaced.workspace.local-harness.password", () -> FakeOpenCode.PASSWORD);
  }

  @AfterAll
  static void stop() {
    OPENCODE.close();
  }

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    workspace =
        workspaces
            .save(new WorkspaceEntity(TestUsers.ADMIN, "Terminal tests", "openrouter"))
            .getId();
    OPENCODE.terminals.clear();
    OPENCODE.requests.clear();
    OPENCODE.rejectTerminals = false;
  }

  @Test
  void createsListsInspectsAndDeletesIndependentShells() throws Exception {
    assertThat(JSON.readTree(send("GET", "", null).body())).isEmpty();
    var first = send("POST", "", "{\"title\":\" Terminal 1 \"}");
    assertThat(first.statusCode()).isEqualTo(201);
    var id = JSON.readTree(first.body()).path("id").asString();
    assertThat(first.headers().firstValue("Location").orElseThrow()).endsWith("/terminals/" + id);
    assertThat(JSON.readTree(first.body()).path("title").asString()).isEqualTo("Terminal 1");
    assertThat(JSON.readTree(first.body()).path("command").asString()).isEqualTo("/bin/bash");
    assertThat(send("POST", "", "{\"title\":\"Terminal 2\"}").statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(send("GET", "", null).body())).hasSize(2);
    assertThat(send("GET", "/" + id, null).statusCode()).isEqualTo(200);
    OPENCODE
        .terminals
        .get(Harness.PROJECT_ROOT + ":" + id)
        .put("status", "exited")
        .put("exitCode", 0);
    assertThat(JSON.readTree(send("GET", "/" + id, null).body()).path("exitCode").asInt()).isZero();
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/" + id, null).statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(send("GET", "", null).body())).hasSize(1);
    assertThat(OPENCODE.requests)
        .allSatisfy(
            request -> {
              assertThat(request.auth()).isEqualTo("Basic b3BlbmNvZGU6cHc=");
              if (request.path().startsWith("/api/pty"))
                assertThat(request.directory()).isEqualTo(Harness.PROJECT_ROOT);
            });
  }

  @Test
  void validatesInputAndDoesNotHideUpstreamFailures() throws Exception {
    assertThat(send("POST", "", "{\"title\":\" \"}").statusCode()).isEqualTo(400);
    assertThat(send("POST", "", "{\"title\":\"" + "x".repeat(101) + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("GET", "/bad-id", null).statusCode()).isEqualTo(400);
    assertThat(OPENCODE.terminals).isEmpty();
    OPENCODE.rejectTerminals = true;
    assertThat(send("GET", "", null).statusCode()).isEqualTo(503);
    assertThat(send("POST", "", "{\"title\":\"Shell\"}").statusCode()).isEqualTo(503);
    assertThat(send("DELETE", "/pty_missing", null).statusCode()).isEqualTo(503);
    OPENCODE.rejectTerminals = false;
    workspace = UUID.randomUUID();
    assertThat(send("GET", "", null).statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> send(String method, String suffix, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/workspaces/"
                        + workspace
                        + "/terminals"
                        + suffix))
            .header("Authorization", TestUsers.bearer(port))
            .header("Content-Type", "application/json")
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build(),
        BodyHandlers.ofString());
  }
}

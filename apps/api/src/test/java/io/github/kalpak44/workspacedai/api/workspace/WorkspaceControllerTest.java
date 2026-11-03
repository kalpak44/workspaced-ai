package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class WorkspaceControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void emptyTable() {
    jdbc.sql("DELETE FROM workspace").update();
  }

  @Test
  void createsAndListsInCreationOrder() throws Exception {
    var created = send("POST", "", "{\"name\":\"  Research  \"}");
    assertThat(created.statusCode()).isEqualTo(201);
    var research = JSON.readValue(created.body(), Workspace.class);
    assertThat(research.name()).isEqualTo("Research");
    assertThat(created.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/api/v1/workspaces/" + research.id()));

    send("POST", "", "{\"name\":\"Docs\"}");

    assertThat(list()).extracting(Workspace::name).containsExactly("Research", "Docs");
  }

  @Test
  void renames() throws Exception {
    var id = create("Old");
    var renamed = send("PUT", "/" + id, "{\"name\":\"New\"}");
    assertThat(renamed.statusCode()).isEqualTo(200);
    assertThat(JSON.readValue(renamed.body(), Workspace.class)).isEqualTo(new Workspace(id, "New"));
    assertThat(list()).extracting(Workspace::name).containsExactly("New");
  }

  @Test
  void deletes() throws Exception {
    var id = create("Gone");
    assertThat(send("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(list()).isEmpty();
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
    assertThat(send("POST", "", "{\"name\":\"   \"}").statusCode()).isEqualTo(400);
    assertThat(send("POST", "", "{\"name\":\"" + "x".repeat(101) + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "", "{}").statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsAnIdThatIsNotAUuid() throws Exception {
    assertThat(send("DELETE", "/not-a-uuid", null).statusCode()).isEqualTo(400);
  }

  private UUID create(String name) throws Exception {
    var response = send("POST", "", "{\"name\":\"" + name + "\"}");
    return JSON.readValue(response.body(), Workspace.class).id();
  }

  private List<Workspace> list() throws Exception {
    return JSON.readValue(send("GET", "", null).body(), new TypeReference<>() {});
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    var body = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces" + path))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}

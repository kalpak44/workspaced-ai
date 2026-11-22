package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class AiConnectorsTest {
  @LocalServerPort int port;
  @Autowired AiConnectorRepository connectors;
  @Autowired WorkspaceRepository workspaces;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ApiKeyCheck check;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    connectors.deleteAll();
    when(check.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void storesEncryptedKeysAndOnlyReturnsMetadata() throws Exception {
    var created =
        send(
            "POST",
            "/ai-connectors",
            """
        {"name":" Work ","provider":"openrouter","apiKey":" private-key "}
        """,
            TestUsers.bearer(port));
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body()).doesNotContain("private-key", "encryptedKey", "apiKey");
    assertThat(json.readTree(created.body()).get("name").asString()).isEqualTo("Work");
    var sealed = jdbc.queryForObject("select encrypted_key from ai_connector", String.class);
    assertThat(sealed).doesNotContain("private-key");
    assertThat(send("GET", "/ai-connectors", null, TestUsers.bearer(port)).body())
        .contains("Work")
        .doesNotContain("private-key");
  }

  @Test
  void isolatesConnectorsAndRejectsAnotherUsersKeyDuringCreation() throws Exception {
    var id = create();
    var other = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    var auth = TestUsers.bearer(other);
    assertThat(send("GET", "/ai-connectors", null, auth).body()).isEqualTo("[]");
    assertThat(send("DELETE", "/ai-connectors/" + id, null, auth).statusCode()).isEqualTo(404);
    assertThat(
            send(
                    "POST",
                    "/workspaces",
                    "{\"name\":\"Other\",\"modelProvider\":\"openrouter\",\"connectorId\":\""
                        + id
                        + "\"}",
                    auth)
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void reusesConnectorAndKeepsWorkspaceWhenItIsDeleted() throws Exception {
    var id = create();
    var created =
        send(
            "POST",
            "/workspaces",
            "{\"name\":\"Research\",\"modelProvider\":\"openrouter\",\"connectorId\":\""
                + id
                + "\"}",
            TestUsers.bearer(port));
    assertThat(created.statusCode()).isEqualTo(201);
    var workspaceId = json.readTree(created.body()).get("id").asString();
    var path = "/workspaces/" + workspaceId + "/ai-connector";
    assertThat(send("GET", path, null, TestUsers.bearer(port)).body()).contains(id);
    assertThat(
            send("PUT", path, "{\"connectorId\":\"" + id + "\"}", TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            workspaces
                .findById(UUID.fromString(workspaceId))
                .orElseThrow()
                .isSecretsPendingRestart())
        .isTrue();
    assertThat(send("DELETE", "/ai-connectors/" + id, null, TestUsers.bearer(port)).statusCode())
        .isEqualTo(204);
    assertThat(send("GET", path, null, TestUsers.bearer(port)).statusCode()).isEqualTo(204);
    assertThat(workspaces.existsById(UUID.fromString(workspaceId))).isTrue();
  }

  @Test
  void rejectsInvalidKeyAndAmbiguousWorkspaceCredentials() throws Exception {
    when(check.accepts(any(), any())).thenReturn(false);
    assertThat(
            send(
                    "POST",
                    "/ai-connectors",
                    "{\"name\":\"Bad\",\"provider\":\"openrouter\",\"apiKey\":\"bad\"}",
                    TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(422);
    assertThat(connectors.count()).isZero();
    assertThat(
            send(
                    "POST",
                    "/ai-connectors",
                    "{\"name\":\" \",\"provider\":\"openrouter\",\"apiKey\":\"bad\"}",
                    TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(400);
    when(check.accepts(any(), any())).thenReturn(true);
    var id = create();
    assertThat(
            send(
                    "POST",
                    "/workspaces",
                    "{\"name\":\"Research\",\"modelProvider\":\"openrouter\",\"apiKey\":\"key\",\"connectorId\":\""
                        + id
                        + "\"}",
                    TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(400);
  }

  String create() throws Exception {
    var response =
        send(
            "POST",
            "/ai-connectors",
            "{\"name\":\"Work\",\"provider\":\"openrouter\",\"apiKey\":\"private-key\"}",
            TestUsers.bearer(port));
    assertThat(response.statusCode()).isEqualTo(201);
    return json.readTree(response.body()).get("id").asString();
  }

  HttpResponse<String> send(String method, String path, String body, String auth) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

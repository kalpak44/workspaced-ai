package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterKeyMetrics;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterManagement;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class AiConnectorsTest {
  @LocalServerPort int port;
  @Autowired AiConnectorRepository connectors;
  @Autowired WorkspaceRepository workspaces;
  @Autowired UserRepository users;
  @Autowired EntityManager entityManager;
  @MockitoBean OpenRouterManagement management;
  @MockitoBean ApiKeyCheck check;
  @MockitoBean OpenRouterMetrics metrics;
  @MockitoBean ChatGptDeviceAuth chatGpt;
  @Autowired ChatGptLoginRepository logins;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkspaceRuntime runtime;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    connectors.deleteAll();
    when(check.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void connectsChatGptWithoutLeakingTokensAndScopesTheProfileToOneWorkspace() throws Exception {
    var auth = TestUsers.bearer(port);
    var workspace =
        workspaces
            .saveAndFlush(
                new io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity(
                    TestUsers.ADMIN, "ChatGPT box", "openrouter", "UTC"))
            .getId();
    org.mockito.Mockito.doReturn(
            java.util.Optional.of(
                new io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint(
                    URI.create("http://localhost:4096"), "test")))
        .when(runtime)
        .harness(workspace);
    when(chatGpt.start())
        .thenReturn(
            json.readTree(
                "{\"device_auth_id\":\"private-device-id\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"5\"}"));
    when(chatGpt.poll(any()))
        .thenReturn(
            "{\"type\":\"oauth\",\"methodID\":\"chatgpt-headless\",\"access\":\"private-access\",\"refresh\":\"private-refresh\",\"expires\":9999999999999}");
    var route = "/workspaces/" + workspace + "/chatgpt";
    assertThat(
            send(
                    "POST",
                    route,
                    "{\"name\":\"Personal plan\",\"providerNoticeAccepted\":false}",
                    auth)
                .statusCode())
        .isEqualTo(400);
    var started =
        send("POST", route, "{\"name\":\"Personal plan\",\"providerNoticeAccepted\":true}", auth);
    assertThat(started.statusCode()).isEqualTo(201);
    assertThat(started.body())
        .contains("ABCD-EFGH")
        .doesNotContain("private-device-id", "refresh", "access_token");
    var id = UUID.fromString(json.readTree(started.body()).path("id").asString());
    assertThat(logins.findById(id).orElseThrow().encryptedState)
        .doesNotContain("private-device-id", "ABCD-EFGH");
    assertThat(
            send("POST", route, "{\"name\":\"Again\",\"providerNoticeAccepted\":true}", auth)
                .statusCode())
        .isEqualTo(409);
    assertThat(send("GET", route + "/" + id, null, auth).body()).contains("pending");
    verify(chatGpt, never()).poll(any());
    var other = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    assertThat(send("GET", route + "/" + id, null, TestUsers.bearer(other)).statusCode())
        .isEqualTo(404);
    jdbc.update(
        "update chatgpt_login set next_poll_at=? where id=?",
        java.sql.Timestamp.from(java.time.Instant.EPOCH),
        id);
    var done = send("GET", route + "/" + id, null, auth);
    assertThat(done.statusCode()).isEqualTo(200);
    assertThat(done.body())
        .contains("complete")
        .doesNotContain("private-access", "private-refresh");
    var connector = UUID.fromString(json.readTree(done.body()).path("connectorId").asString());
    var saved = connectors.findById(connector).orElseThrow();
    assertThat(saved.isChatGpt()).isTrue();
    assertThat(saved.getWorkspaceId()).isEqualTo(workspace);
    assertThat(saved.encryptedKey()).doesNotContain("private-access", "private-refresh");
    assertThat(logins.findById(id).orElseThrow().encryptedState).isNull();
    assertThat(send("GET", route + "/" + id, null, auth).body()).isEqualTo(done.body());
    org.mockito.Mockito.verify(chatGpt, org.mockito.Mockito.times(1)).poll(any());
    assertThat(
            send(
                    "PUT",
                    "/workspaces/" + workspace + "/ai-connector",
                    "{\"connectorId\":\"" + connector + "\"}",
                    auth)
                .statusCode())
        .isEqualTo(200);
    var another =
        workspaces
            .saveAndFlush(
                new io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity(
                    TestUsers.ADMIN, "Another", "openrouter", "UTC"))
            .getId();
    assertThat(send("GET", "/workspaces/" + another + "/ai-connector/status", null, auth).body())
        .doesNotContain(connector.toString());
    assertThat(
            send(
                    "PUT",
                    "/workspaces/" + another + "/ai-connector",
                    "{\"connectorId\":\"" + connector + "\"}",
                    auth)
                .statusCode())
        .isEqualTo(409);
    assertThat(
            send(
                    "POST",
                    "/workspaces",
                    "{\"name\":\"Clone\",\"modelProvider\":\"openai\",\"connectorId\":\""
                        + connector
                        + "\"}",
                    auth)
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void expiresAndCancelsChatGptAttemptsWithoutExchangingThem() throws Exception {
    var workspace =
        workspaces
            .saveAndFlush(
                new io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity(
                    TestUsers.ADMIN, "Pending", "openrouter", "UTC"))
            .getId();
    org.mockito.Mockito.doReturn(
            java.util.Optional.of(
                new io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint(
                    URI.create("http://localhost:4096"), "test")))
        .when(runtime)
        .harness(workspace);
    when(chatGpt.start())
        .thenReturn(
            json.readTree("{\"device_auth_id\":\"device\",\"user_code\":\"CODE\",\"interval\":5}"));
    var route = "/workspaces/" + workspace + "/chatgpt";
    var started =
        send(
            "POST",
            route,
            "{\"name\":\"Plan\",\"providerNoticeAccepted\":true}",
            TestUsers.bearer(port));
    var id = json.readTree(started.body()).path("id").asString();
    assertThat(send("DELETE", route + "/" + id, null, TestUsers.bearer(port)).statusCode())
        .isEqualTo(204);
    assertThat(send("GET", route + "/" + id, null, TestUsers.bearer(port)).body())
        .contains("cancelled");
    started =
        send(
            "POST",
            route,
            "{\"name\":\"Plan\",\"providerNoticeAccepted\":true}",
            TestUsers.bearer(port));
    var expired = UUID.fromString(json.readTree(started.body()).path("id").asString());
    jdbc.update(
        "update chatgpt_login set expires_at=? where id=?",
        java.sql.Timestamp.from(java.time.Instant.EPOCH),
        expired);
    assertThat(send("GET", route + "/" + expired, null, TestUsers.bearer(port)).body())
        .contains("expired");
    assertThat(logins.findById(expired).orElseThrow().encryptedState).isNull();
    verify(chatGpt, never()).poll(any());
  }

  @Test
  void discoversAndCreatesOpenAiConnectorsByDefault() throws Exception {
    var auth = TestUsers.bearer(port);
    var providers = send("GET", "/model-providers?connectors=true", null, auth);
    assertThat(providers.statusCode()).isEqualTo(200);
    assertThat(json.readTree(providers.body())).hasSize(2);
    assertThat(providers.body()).contains("openai", "OPENAI_API_KEY");
    var created =
        send(
            "POST",
            "/ai-connectors",
            """
        {"name":"OpenAI work","provider":"openai","providerNoticeAccepted":true,"apiKey":"openai-test-key"}
        """,
            auth);
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body()).contains("openai").doesNotContain("openai-test-key");
  }

  @Test
  void storesEncryptedKeysAndOnlyReturnsMetadata() throws Exception {
    var created =
        send(
            "POST",
            "/ai-connectors",
            """
        {"name":" Work ","provider":"openrouter","providerNoticeAccepted":true,"apiKey":" private-key "}
        """,
            TestUsers.bearer(port));
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body()).doesNotContain("private-key", "encryptedKey", "apiKey");
    assertThat(json.readTree(created.body()).get("name").asString()).isEqualTo("Work");
    var sealed = connectors.findAll().getFirst().encryptedKey();
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
    assertThat(send("GET", "/ai-connectors/" + id + "/metrics", null, auth).statusCode())
        .isEqualTo(404);
    verify(metrics, never()).read(any());
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
  void reusesConnectorAndRefusesImplicitTrialFallbackOnDisconnect() throws Exception {
    when(management.configured()).thenReturn(true);
    when(management.createTrial(any()))
        .thenReturn(
            new io.github.kalpak44.workspacedai.api.provider.OpenRouterManagement.IssuedKey(
                "trial-key", "trial-hash"));
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
        .isEqualTo(409);
    assertThat(send("GET", path, null, TestUsers.bearer(port)).body()).contains(id);
    assertThat(workspaces.existsById(UUID.fromString(workspaceId))).isTrue();
    verify(runtime, never()).replaceProviderKey(any(), any());
    verify(runtime, never()).putSecret(any(), any(), any());
  }

  @Test
  void rejectsInvalidKeyAndAmbiguousWorkspaceCredentials() throws Exception {
    when(check.accepts(any(), any())).thenReturn(false);
    assertThat(
            send(
                    "POST",
                    "/ai-connectors",
                    "{\"name\":\"Bad\",\"provider\":\"openrouter\",\"providerNoticeAccepted\":true,\"apiKey\":\"bad\"}",
                    TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(422);
    assertThat(connectors.count()).isZero();
    assertThat(
            send(
                    "POST",
                    "/ai-connectors",
                    "{\"name\":\" \",\"provider\":\"openrouter\",\"providerNoticeAccepted\":true,\"apiKey\":\"bad\"}",
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

  @Test
  void requiresAcknowledgementAndRecordsItsVersion() throws Exception {
    var body =
        """
      {"name":"Key","provider":"openrouter","apiKey":"private-key"}
      """;
    assertThat(send("POST", "/ai-connectors", body, TestUsers.bearer(port)).statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/ai-connectors",
                    body.replace("\"name\":", "\"providerNoticeAccepted\":false,\"name\":"),
                    TestUsers.bearer(port))
                .statusCode())
        .isEqualTo(400);
    assertThat(connectors.count()).isZero();
    create();
    var cb = entityManager.getCriteriaBuilder();
    var query = cb.createTupleQuery();
    var row = query.from(AiConnectorEntity.class);
    var acceptedAt = row.get(AiConnectorEntity_.providerNoticeAcceptedAt);
    var version = row.get(AiConnectorEntity_.providerNoticeVersion);
    var notice =
        entityManager.createQuery(query.multiselect(acceptedAt, version)).getSingleResult();
    assertThat(notice.get(acceptedAt)).isNotNull();
    assertThat(notice.get(version)).isEqualTo("ai-provider-notice-v1");
  }

  @Test
  void readsOnlyTheOwnersUsageWithoutReturningCredentials() throws Exception {
    var id = create();
    when(metrics.read("private-key"))
        .thenReturn(
            new OpenRouterKeyMetrics(
                new BigDecimal("2.5"),
                null,
                null,
                null,
                new BigDecimal("10"),
                new BigDecimal("7.5"),
                "monthly",
                null,
                false,
                BigDecimal.ZERO,
                false,
                null,
                null));
    var response = send("GET", "/ai-connectors/" + id + "/metrics", null, TestUsers.bearer(port));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(json.readTree(response.body()).get("usage").decimalValue())
        .isEqualByComparingTo("2.5");
    assertThat(response.body()).doesNotContain("private-key", "encryptedKey");
    verify(metrics).read("private-key");
  }

  @Test
  void launchesADraftWithTheOwnersSavedTrialConnectorWithoutSendingItsKey() throws Exception {
    when(management.configured()).thenReturn(true);
    when(management.createTrial(any()))
        .thenReturn(
            new io.github.kalpak44.workspacedai.api.provider.OpenRouterManagement.IssuedKey(
                "trial-key", "trial-hash"));
    var listed = send("GET", "/ai-connectors", null, TestUsers.bearer(port));
    var trialId = json.readTree(listed.body()).get(0).get("id").asString();
    assertThat(
            send("DELETE", "/ai-connectors/" + trialId, null, TestUsers.bearer(port)).statusCode())
        .isEqualTo(409);
    var draft =
        send(
            "POST",
            "/workspaces",
            "{\"name\":\"Draft\",\"modelProvider\":\"openrouter\"}",
            TestUsers.bearer(port));
    var workspaceId = json.readTree(draft.body()).get("id").asString();
    var launched =
        send(
            "POST",
            "/workspaces/" + workspaceId + "/launch",
            "{\"connectorId\":\"" + trialId + "\"}",
            TestUsers.bearer(port));
    assertThat(launched.statusCode()).isEqualTo(200);
    assertThat(launched.body()).doesNotContain("trial-key");
    assertThat(workspaces.findById(UUID.fromString(workspaceId)).orElseThrow().getAiConnectorId())
        .isEqualTo(UUID.fromString(trialId));
  }

  String create() throws Exception {
    var response =
        send(
            "POST",
            "/ai-connectors",
            "{\"name\":\"Work\",\"provider\":\"openrouter\",\"providerNoticeAccepted\":true,\"apiKey\":\"private-key\"}",
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

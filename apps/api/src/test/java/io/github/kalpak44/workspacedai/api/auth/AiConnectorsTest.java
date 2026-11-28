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

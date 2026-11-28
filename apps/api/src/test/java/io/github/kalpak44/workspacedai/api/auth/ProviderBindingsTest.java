package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.kalpak44.workspacedai.api.*;
import io.github.kalpak44.workspacedai.api.provider.*;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.*;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "workspaced.ai.openai-api-key-enabled=true",
      "workspaced.ai.provider-reconcile-ms=3600000"
    })
@Import({TestDatabase.class, TestRuntime.class})
class ProviderBindingsTest {
  @LocalServerPort int port;
  @Autowired WorkspaceRepository workspaces;
  @Autowired AiConnectorRepository connectors;
  @Autowired UserRepository users;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;
  @Autowired io.github.kalpak44.workspacedai.api.ClusterLocks locks;
  @Autowired AuthProperties auth;
  @Autowired ProviderBindings bindings;
  @Autowired AiUsage usage;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean WorkspaceRuntime runtime;
  @MockitoBean ApiKeyCheck checks;
  @MockitoBean OpenRouterMetrics metrics;
  @MockitoBean io.github.kalpak44.workspacedai.api.harness.Harness harness;
  UserEntity owner;
  UUID workspace;
  UUID original;
  UUID target;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    connectors.deleteAll();
    owner = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    when(checks.accepts(any(), any())).thenReturn(true);
    when(runtime.statuses(any()))
        .thenAnswer(
            i -> {
              Map<UUID, WorkspaceStatus> m = new HashMap<>();
              for (UUID id : (Collection<UUID>) i.getArgument(0)) m.put(id, WorkspaceStatus.READY);
              return m;
            });
    original = connector("Original", "openrouter", "original-key");
    target = connector("Target", "openai", "target-key");
    var w = new WorkspaceEntity(owner.getId(), "Binding test", "openrouter", "UTC");
    w.setAiConnectorId(original);
    workspace = workspaces.saveAndFlush(w).getId();
    when(runtime.harness(workspace))
        .thenReturn(
            Optional.of(
                new io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint(
                    URI.create("http://localhost:4096"), "test")));
  }

  UUID connector(String name, String provider, String key) {
    return connectors
        .saveAndFlush(
            new AiConnectorEntity(
                owner.getId(),
                name,
                provider,
                new SecretBox(auth.jwtSecret(), "workspaced-ai-connectors").seal(key)))
        .getId();
  }

  HttpResponse<String> request(String method, String route, String body, UserEntity user)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + route))
            .header("Authorization", TestUsers.bearer(user))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void stage(UUID id) throws Exception {
    assertThat(
            request(
                    "PUT",
                    "/workspaces/" + workspace + "/ai-connector",
                    "{\"connectorId\":\"" + id + "\"}",
                    owner)
                .statusCode())
        .isEqualTo(200);
  }

  void restart() throws Exception {
    assertThat(request("POST", "/workspaces/" + workspace + "/restart", null, owner).statusCode())
        .isEqualTo(202);
  }

  WorkspaceEntity row() {
    return workspaces.findById(workspace).orElseThrow();
  }

  void ready(long revision) {
    when(runtime.providerReceipt(workspace))
        .thenReturn(new WorkspaceRuntime.ProviderReceipt(revision, revision));
    bindings.reconcile(workspace);
  }

  void expire() {
    jdbc.update(
        "update workspace set provider_change_deadline=? where id=?",
        java.sql.Timestamp.from(Instant.EPOCH),
        workspace);
  }

  @Test
  void firstChatGptSetupGatesTrafficUntilItsConnectionIsInstalled() throws Exception {
    var row = row();
    row.activateProvider(null, "openai");
    row.setChatGptSetup(true);
    workspaces.saveAndFlush(row);
    var status = request("GET", "/workspaces/" + workspace + "/ai-connector/status", null, owner);
    assertThat(json.readTree(status.body()).path("chatGptSetup").asBoolean()).isTrue();
    assertThatThrownBy(() -> bindings.requireRoutable(workspace))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var credential = "{\"type\":\"oauth\",\"access\":\"test-access\",\"refresh\":\"test-refresh\"}";
    var connector =
        connectors.saveAndFlush(
            AiConnectorEntity.chatGpt(
                owner.getId(),
                workspace,
                "Plan",
                new SecretBox(auth.jwtSecret(), "workspaced-ai-connectors").seal(credential)));
    stage(connector.getId());
    assertThat(row().isChatGptSetup()).isFalse();
    assertThat(row().getAiConnectorId()).isEqualTo(connector.getId());
    assertThat(row().getProviderChange().phase).isEqualTo("NONE");
    verify(harness)
        .activateOpenAiConnection(
            any(), eq("workspaced-" + connector.getId()), eq("Plan"), eq(credential), eq(true));
    verify(runtime, never()).restartProvider(any(), anyLong(), any(), any());
  }

  @Test
  void stagesBeforeRotatingAndActivatesOnlyMatchingReadinessBothDirections() throws Exception {
    stage(target);
    assertThat(row().getAiConnectorId()).isEqualTo(original);
    assertThat(row().getModelProvider()).isEqualTo("openrouter");
    verify(runtime, never()).replaceProviderKey(any(), any());
    verify(runtime, never()).restartProvider(any(), anyLong(), any(), any());
    restart();
    verify(runtime).restartProvider(workspace, 1, "openai", "target-key");
    assertThat(row().getAiConnectorId()).isEqualTo(original);
    assertThat(row().isSecretsPendingRestart()).isTrue();
    assertThatThrownBy(() -> bindings.requireRoutable(workspace))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    ready(0);
    assertThat(row().getAiConnectorId()).isEqualTo(original);
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
    assertThat(row().getModelProvider()).isEqualTo("openai");
    assertThat(row().isSecretsPendingRestart()).isFalse();
    stage(original);
    restart();
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
    ready(2);
    assertThat(row().getAiConnectorId()).isEqualTo(original);
  }

  @Test
  void waitsForNativeIntegrationReadinessBeforeAllowingMessages() throws Exception {
    stage(target);
    restart();
    doThrow(new IllegalStateException("integration is still starting"))
        .doNothing()
        .when(harness)
        .activateOpenAiConnection(any(), any(), any(), any(), anyBoolean());
    ready(1);
    assertThat(row().getProviderChange().phase).isEqualTo("APPLYING");
    assertThatThrownBy(() -> bindings.requireRoutable(workspace))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
  }

  @Test
  void activatesNativeChatGptOnlyAfterReadinessAndCanReturnToAnApiKey() throws Exception {
    var oauth =
        connectors
            .saveAndFlush(
                AiConnectorEntity.chatGpt(
                    owner.getId(),
                    workspace,
                    "ChatGPT",
                    new SecretBox(auth.jwtSecret(), "workspaced-ai-connectors")
                        .seal("native-oauth-value")))
            .getId();
    stage(oauth);
    verify(harness, never()).activateOpenAiConnection(any(), any(), any(), any(), anyBoolean());
    restart();
    verify(runtime).restartProvider(workspace, 1, "openai-chatgpt", "managed-chatgpt");
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(oauth);
    verify(harness)
        .activateOpenAiConnection(
            any(), eq("workspaced-" + oauth), eq("ChatGPT"), eq("native-oauth-value"), eq(true));
    stage(target);
    restart();
    ready(2);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
    verify(harness)
        .activateOpenAiConnection(
            any(), eq("workspaced-" + target), eq("Target"), eq("target-key"), eq(false));
  }

  @Test
  void sameProviderChangeStaysPendingAndConcurrentSelectionIsRejected() throws Exception {
    var another = connector("Another", "openrouter", "another-key");
    stage(another);
    stage(original);
    stage(another);
    restart();
    assertThat(
            request(
                    "PUT",
                    "/workspaces/" + workspace + "/ai-connector",
                    "{\"connectorId\":\"" + target + "\"}",
                    owner)
                .statusCode())
        .isEqualTo(409);
    assertThat(row().getAiConnectorId()).isEqualTo(original);
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(another);
  }

  @Test
  void failedRestartRollsBackOnlyAuthorizedSnapshotAndLateReadinessCannotActivate()
      throws Exception {
    stage(target);
    restart();
    expire();
    bindings.reconcile(workspace);
    verify(runtime).restartProvider(workspace, 2, "openrouter", "original-key");
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(original);
    assertThat(row().getProviderChange().phase).isEqualTo("ROLLING_BACK");
    ready(2);
    assertThat(row().getProviderChange().phase).isEqualTo("STAGED");
    assertThat(row().getProviderChange().error).isEqualTo("RESTART_FAILED_ROLLED_BACK");
    restart();
    ready(3);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
  }

  @Test
  void revokedPreviousCredentialFailsClosedAndDisconnectNeverFallsBack() throws Exception {
    stage(target);
    restart();
    when(checks.accepts(ModelProvider.OPENROUTER, "original-key")).thenReturn(false);
    expire();
    bindings.reconcile(workspace);
    assertThat(row().getProviderChange().phase).isEqualTo("BLOCKED");
    verify(runtime, never()).restartProvider(workspace, 2, "openrouter", "original-key");
    assertThatThrownBy(() -> bindings.requireRoutable(workspace))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(request("DELETE", "/ai-connectors/" + original, null, owner).statusCode())
        .isEqualTo(409);
  }

  @Test
  void validatesOwnerAndUsabilityBeforeStagingAndNeverReturnsSecrets() throws Exception {
    var stranger = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    assertThat(
            request(
                    "PUT",
                    "/workspaces/" + workspace + "/ai-connector",
                    "{\"connectorId\":\"" + target + "\"}",
                    stranger)
                .statusCode())
        .isEqualTo(404);
    when(checks.accepts(ModelProvider.OPENAI, "target-key")).thenReturn(false);
    assertThat(
            request(
                    "PUT",
                    "/workspaces/" + workspace + "/ai-connector",
                    "{\"connectorId\":\"" + target + "\"}",
                    owner)
                .statusCode())
        .isEqualTo(422);
    assertThat(row().getProviderChange().phase).isEqualTo("NONE");
    when(checks.accepts(any(), any())).thenReturn(true);
    stage(target);
    var status = request("GET", "/workspaces/" + workspace + "/ai-connector/status", null, owner);
    assertThat(status.statusCode()).isEqualTo(200);
    assertThat(status.body())
        .contains("Original", "Target", "STAGED")
        .doesNotContain("original-key", "target-key", "encryptedKey", "targetKey");
  }

  @Test
  void transportFailureRetainsDurableIntentAndRetryUsesSameRevision() throws Exception {
    stage(target);
    doThrow(new IllegalStateException("offline"))
        .when(runtime)
        .restartProvider(workspace, 1, "openai", "target-key");
    restart();
    assertThat(row().getProviderChange().phase).isEqualTo("APPLYING");
    restart();
    verify(runtime, times(2)).restartProvider(workspace, 1, "openai", "target-key");
    ready(1);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
  }

  @Test
  void survivesCoordinatorRestartAndKeepsNewerSecretChangesPending() throws Exception {
    stage(target);
    restart();
    tx.executeWithoutResult(
        _ -> {
          var w = workspaces.findLockedById(workspace).orElseThrow();
          w.setSecretsPendingRestart(true);
        });
    when(runtime.providerReceipt(workspace))
        .thenReturn(new WorkspaceRuntime.ProviderReceipt(1L, 1L));
    var recovered =
        new ProviderBindings(
            workspaces,
            harness,
            usage,
            connectors,
            runtime,
            checks,
            auth,
            tx,
            locks,
            true,
            java.time.Duration.ofMinutes(3));
    recovered.reconcile(workspace);
    assertThat(row().getAiConnectorId()).isEqualTo(target);
    assertThat(row().isSecretsPendingRestart()).isTrue();
  }

  @Test
  void failedLegacySecretRestartKeepsRestartRequired() throws Exception {
    tx.executeWithoutResult(
        _ -> workspaces.findLockedById(workspace).orElseThrow().setSecretsPendingRestart(true));
    doThrow(new IllegalStateException("no VM")).when(runtime).restart(workspace);
    assertThat(request("POST", "/workspaces/" + workspace + "/restart", null, owner).statusCode())
        .isEqualTo(409);
    assertThat(row().isSecretsPendingRestart()).isTrue();
  }

  @Test
  void newerUnknownRuntimeRevisionStaysBlockedInsteadOfOverwritingIt() throws Exception {
    stage(target);
    restart();
    when(runtime.providerReceipt(workspace))
        .thenReturn(new WorkspaceRuntime.ProviderReceipt(99L, 99L));
    bindings.reconcile(workspace);
    assertThat(row().getProviderChange().phase).isEqualTo("BLOCKED");
    assertThat(row().getProviderChange().error).isEqualTo("SUPERSEDED_RUNTIME");
    assertThat(request("POST", "/workspaces/" + workspace + "/restart", null, owner).statusCode())
        .isEqualTo(409);
    verify(runtime, never()).restartProvider(workspace, 2, "openrouter", "original-key");
  }

  @Test
  void evenResettingTheModelIsRejectedWhileProviderReadinessIsUnconfirmed() throws Exception {
    stage(target);
    restart();
    assertThat(
            request("PUT", "/workspaces/" + workspace + "/model", "{\"model\":null}", owner)
                .statusCode())
        .isEqualTo(409);
    assertThat(row().getProviderChange().phase).isEqualTo("APPLYING");
  }

  @Test
  void restartCannotRotateWhileAnAcceptedPromptIsBeingDispatched() throws Exception {
    stage(target);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var dispatch =
          executor.submit(
              () ->
                  bindings.route(
                      workspace,
                      () -> {
                        entered.countDown();
                        try {
                          release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                      }));
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(
                request("POST", "/workspaces/" + workspace + "/restart", null, owner).statusCode())
            .isEqualTo(409);
        assertThat(row().getProviderChange().phase).isEqualTo("STAGED");
        verify(runtime, never()).restartProvider(any(), anyLong(), any(), any());
      } finally {
        release.countDown();
      }
      dispatch.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    restart();
    verify(runtime).restartProvider(workspace, 1, "openai", "target-key");
  }
}

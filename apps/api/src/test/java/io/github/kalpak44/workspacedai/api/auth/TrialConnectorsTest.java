package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterManagement;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class TrialConnectorsTest {
  @Autowired TrialConnectors trials;
  @Autowired AiConnectorService service;
  @Autowired AiConnectorRepository connectors;
  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired AuthService auth;
  @MockitoBean OpenRouterManagement management;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoSpyBean WorkspaceRuntime runtime;
  UUID owner;

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    connectors.deleteAll();
    owner = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com")).getId();
    when(management.configured()).thenReturn(true);
    when(management.createTrial(any()))
        .thenAnswer(
            invocation ->
                new OpenRouterManagement.IssuedKey(
                    "trial-secret-" + invocation.getArgument(0),
                    "hash-" + invocation.getArgument(0)));
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("test")
                    .header("alg", "none")
                    .subject(owner.toString())
                    .build()));
  }

  @org.junit.jupiter.api.AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void createsOneEncryptedProtectedTrialAndNeverRefillsIt() {
    var first = trials.ensure(owner).orElseThrow();
    assertThat(first.isTrial()).isTrue();
    assertThat(first.encryptedKey()).doesNotContain("trial-secret");
    assertThat(trials.ensure(owner).orElseThrow().getId()).isEqualTo(first.getId());
    assertThat(service.list())
        .first()
        .satisfies(connector -> assertThat(connector.trial()).isTrue());
    verify(management).createTrial(owner);
    assertThatThrownBy(() -> service.delete(first.getId()))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(connectors.existsById(first.getId())).isTrue();
    verify(management, never()).revoke(any());
  }

  @Test
  void serializesConcurrentProvisioningAcrossRequests() {
    var results =
        List.of(
            CompletableFuture.supplyAsync(() -> trials.ensure(owner).orElseThrow().getId()),
            CompletableFuture.supplyAsync(() -> trials.ensure(owner).orElseThrow().getId()),
            CompletableFuture.supplyAsync(() -> trials.ensure(owner).orElseThrow().getId()));
    assertThat(results.stream().map(CompletableFuture::join).distinct().toList()).hasSize(1);
    verify(management).createTrial(owner);
  }

  @Test
  void provisionsAfterVerifiedSignupAndReusesTheKeyOnLaterSignIns() {
    var subject = "trial-test|" + UUID.randomUUID();
    var profile = new Auth0Users.Profile("new@example.com", "New", "User", "");
    var first = auth.signIn(subject, profile, true, Role.USER);
    var account = users.findByIdpSubject(subject).orElseThrow();
    assertThat(first.created()).isTrue();
    assertThat(connectors.findByOwnerIdAndTrialTrue(account.getId())).isPresent();
    auth.signIn(subject, profile, true, Role.USER);
    verify(management).createTrial(account.getId());
  }

  @Test
  void keepsSignInWorkingWhenManagementServiceIsUnavailable() {
    when(management.createTrial(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable"));
    var subject = "trial-failure|" + UUID.randomUUID();
    assertThat(
            auth.signIn(
                    subject, new Auth0Users.Profile("new@example.com", "", "", ""), true, Role.USER)
                .created())
        .isTrue();
    assertThat(
            connectors.findByOwnerIdAndTrialTrue(
                users.findByIdpSubject(subject).orElseThrow().getId()))
        .isEmpty();
  }

  @Test
  void fallsBackAllReferencingWorkspacesAndMarksThemForRestart() {
    var trial = trials.ensure(owner).orElseThrow();
    var personal =
        connectors.saveAndFlush(
            new AiConnectorEntity(
                owner,
                "Personal",
                "openrouter",
                new SecretBox(
                        "test-only-jwt-signing-key-not-for-production", "workspaced-ai-connectors")
                    .seal("personal-secret")));
    var first = workspace(personal.getId());
    var second = workspace(personal.getId());
    var untouched = workspace(null);
    service.delete(personal.getId());
    assertThat(connectors.existsById(personal.getId())).isFalse();
    for (var id : List.of(first, second)) {
      var workspace = workspaces.findById(id).orElseThrow();
      assertThat(workspace.getAiConnectorId()).isEqualTo(trial.getId());
      assertThat(workspace.isSecretsPendingRestart()).isTrue();
      verify(runtime).replaceProviderKey(id, "trial-secret-" + owner);
    }
    assertThat(workspaces.findById(untouched).orElseThrow().isSecretsPendingRestart()).isFalse();
    verify(management).createTrial(owner);
    verify(management, never()).revoke(any());
  }

  @Test
  void refusesDeletionWhenFallbackCannotBeProvisioned() {
    when(management.configured()).thenReturn(false);
    var personal =
        connectors.saveAndFlush(
            new AiConnectorEntity(owner, "Personal", "openrouter", "encrypted"));
    var workspace = workspace(personal.getId());
    assertThatThrownBy(() -> service.delete(personal.getId()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(connectors.existsById(personal.getId())).isTrue();
    assertThat(workspaces.findById(workspace).orElseThrow().getAiConnectorId())
        .isEqualTo(personal.getId());
    verify(runtime, never()).replaceProviderKey(any(), any());
  }

  @Test
  void restoresAlreadyChangedVMsIfAnotherFallbackUpdateFails() {
    trials.ensure(owner);
    var personal =
        connectors.saveAndFlush(
            new AiConnectorEntity(
                owner,
                "Personal",
                "openrouter",
                new SecretBox(
                        "test-only-jwt-signing-key-not-for-production", "workspaced-ai-connectors")
                    .seal("personal-secret")));
    var first = workspace(personal.getId());
    var second = workspace(personal.getId());
    doThrow(new IllegalStateException("no VM"))
        .when(runtime)
        .replaceProviderKey(second, "trial-secret-" + owner);
    assertThatThrownBy(() -> service.delete(personal.getId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(connectors.existsById(personal.getId())).isTrue();
    for (var id : List.of(first, second))
      assertThat(workspaces.findById(id).orElseThrow().getAiConnectorId())
          .isEqualTo(personal.getId());
  }

  UUID workspace(UUID connectorId) {
    var row = new WorkspaceEntity(owner, "Workspace", "openrouter", "UTC", false);
    row.setAiConnectorId(connectorId);
    return workspaces.saveAndFlush(row).getId();
  }
}

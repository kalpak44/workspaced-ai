package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.kalpak44.workspacedai.api.*;
import io.github.kalpak44.workspacedai.api.harness.*;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.session.*;
import io.github.kalpak44.workspacedai.api.workspace.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "workspaced.ai.usage-collect-ms=3600000")
@Import({TestDatabase.class, TestRuntime.class})
class AiUsageTest {
  @Autowired AiUsage usage;
  @Autowired WorkspaceRepository workspaces;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Harness harness;
  @MockitoBean WorkspaceRuntime runtime;
  @LocalServerPort int port;
  UserEntity owner;
  WorkspaceEntity workspace;
  WorkspaceSessionEntity session;
  UUID connector;
  HarnessEndpoint endpoint = new HarnessEndpoint(URI.create("http://localhost:1"), "inert-test");

  static UsageMessage user(String id) {
    return new UsageMessage(id, "user", null, null, null, null);
  }

  static UsageMessage step(String id, String provider) {
    return new UsageMessage(
        id, "assistant", provider, "model-one", 123L, new UsageMessage.Tokens(12, 8, 2, 3, 0));
  }

  @BeforeEach
  void setup() {
    owner = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    workspace =
        workspaces.saveAndFlush(
            new WorkspaceEntity(owner.getId(), "Usage workspace", "openrouter", "UTC"));
    session =
        sessions.saveAndFlush(
            new WorkspaceSessionEntity(
                UUID.randomUUID(),
                workspace.getId(),
                owner.getId(),
                "/home/workspace/sessions/test",
                "ses_test"));
    connector = UUID.randomUUID();
    // Snapshot IDs need not keep connector rows alive after disconnect.
    jdbc.update("UPDATE workspace SET ai_connector_id=NULL WHERE id=?", workspace.getId());
    when(runtime.harness(workspace.getId())).thenReturn(Optional.of(endpoint));
    when(harness.usageHistory(endpoint, "ses_test")).thenReturn(List.of());
  }

  void binding(String provider, UUID id) {
    // Set a real connector because the workspace FK enforces it, with no credential use.
    jdbc.update(
        "INSERT INTO ai_connector(id,owner_id,name,provider,encrypted_key,trial) VALUES (?,?,?,?,?,false) ON CONFLICT (id) DO NOTHING",
        id,
        owner.getId(),
        "Test",
        provider,
        "inert-sealed-fixture");
    jdbc.update(
        "UPDATE workspace SET model_provider=?,ai_connector_id=? WHERE id=?",
        provider,
        id,
        workspace.getId());
  }

  @Test
  void snapshotsDispatchAcrossProviderAndSameProviderConnectorChangesAndDeduplicates() {
    binding("openrouter", connector);
    var first = usage.prompt(session);
    var nextConnector = UUID.randomUUID();
    binding("openai", nextConnector);
    var second = usage.prompt(session);
    var thirdConnector = UUID.randomUUID();
    binding("openai", thirdConnector);
    var third = usage.prompt(session);
    var history =
        List.of(
            user(first),
            step("msg_one", "openrouter"),
            user(second),
            step("msg_two", "openai"),
            user(third),
            step("msg_three", "openai"));
    usage.ingest(session, history);
    usage.ingest(session, history);
    // A replay that changes model/tokens cannot rewrite previously recorded attribution.
    usage.ingest(session, List.of(user(third), step("msg_one", "openai")));
    assertThat(usage.totals(owner.getId()))
        .hasSize(3)
        .allSatisfy(
            t -> {
              assertThat(t.steps()).isEqualTo(1);
              assertThat(t.input()).isEqualByComparingTo("12");
              assertThat(t.workspaceName()).isEqualTo("Usage workspace");
            });
    assertThat(usage.totals(owner.getId()))
        .extracting(AiUsage.Total::connectorId)
        .containsExactlyInAnyOrder(connector, nextConnector, thirdConnector);
  }

  @Test
  void excludesUnknownInputsWrongProvidersIncompleteAndMissingReceipts() {
    var input = usage.prompt(session);
    usage.ingest(
        session,
        List.of(
            step("old", "openrouter"),
            user(input),
            step("wrong", "openai"),
            new UsageMessage(
                "incomplete",
                "assistant",
                "openrouter",
                "model",
                null,
                new UsageMessage.Tokens(1, 2, 0, 0, 0)),
            new UsageMessage("missing", "assistant", "openrouter", "model", 123L, null),
            user("unknown-local-input"),
            step("untracked", "openrouter")));
    assertThat(usage.totals(owner.getId())).isEmpty();
  }

  @Test
  void fencesFormContinuationOnlyAfterAcceptedReplyAndKeepsExistingStepAttribution() {
    binding("openrouter", connector);
    var input = usage.prompt(session);
    var existing = List.of(user(input), step("msg_before_form", "openrouter"));
    when(harness.usageHistory(endpoint, "ses_test")).thenReturn(existing);
    binding("openai", UUID.randomUUID());
    var dispatch = usage.form(session);
    var after = new ArrayList<>(existing);
    after.add(step("msg_after_form", "openai"));
    usage.ingest(session, after);
    assertThat(usage.totals(owner.getId())).hasSize(1);
    usage.acceptForm(dispatch);
    usage.ingest(session, after);
    assertThat(usage.totals(owner.getId())).hasSize(2);
    assertThat(usage.totals(owner.getId()))
        .filteredOn(t -> t.provider().equals("openrouter"))
        .singleElement()
        .satisfies(t -> assertThat(t.connectorId()).isEqualTo(connector));
  }

  @Test
  void collectorRecoversWithoutBrowserAndHistoryDeletionDoesNotEraseLedger() {
    var input = usage.prompt(session);
    when(harness.usageHistory(endpoint, "ses_test"))
        .thenReturn(List.of(user(input), step("msg_complete", "openrouter")));
    usage.reconcile();
    sessions.deleteById(session.getId());
    workspaces.deleteById(workspace.getId());
    assertThat(usage.totals(owner.getId()))
        .singleElement()
        .satisfies(t -> assertThat(t.workspaceName()).isEqualTo("Deleted workspace"));
    // Failed/unavailable collection defers rather than inventing zero-valued usage.
    usage.reconcile();
  }

  @Test
  void restartFenceKeepsObservedLateCompletionsButRejectsUntrackedResumption() {
    var input = usage.prompt(session);
    var partial =
        new UsageMessage("msg_started", "assistant", "openrouter", "model-one", null, null);
    when(harness.usageHistory(endpoint, "ses_test")).thenReturn(List.of(user(input), partial));
    usage.fence(workspace.getId());
    usage.ingest(
        session,
        List.of(
            user(input),
            step("msg_started", "openrouter"),
            step("msg_resumed_without_dispatch", "openrouter")));
    assertThat(usage.totals(owner.getId()))
        .singleElement()
        .satisfies(t -> assertThat(t.steps()).isEqualTo(1));
    var next = usage.prompt(session);
    usage.ingest(session, List.of(user(next), step("msg_new_dispatch", "openrouter")));
    assertThat(usage.totals(owner.getId()))
        .singleElement()
        .satisfies(t -> assertThat(t.steps()).isEqualTo(2));
  }

  @Test
  void httpLedgerIsAuthenticatedOwnerScopedAndContainsNoContentsOrCredentials() throws Exception {
    var input = usage.prompt(session);
    usage.ingest(session, List.of(user(input), step("msg_complete", "openrouter")));
    var other = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com"));
    var client = HttpClient.newHttpClient();
    var uri = URI.create("http://localhost:" + port + "/api/v1/ai-usage");
    var own =
        client.send(
            HttpRequest.newBuilder(uri).header("Authorization", TestUsers.bearer(owner)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(own.statusCode()).isEqualTo(200);
    assertThat(own.body())
        .contains("Usage workspace", "model-one")
        .doesNotContain("encrypted", "inert", "ses_test", input);
    assertThat(own.headers().firstValue("Cache-Control")).hasValue("no-store");
    var foreign =
        client.send(
            HttpRequest.newBuilder(uri).header("Authorization", TestUsers.bearer(other)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(foreign.body()).isEqualTo("[]");
    assertThat(
            client
                .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
  }
}

package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.NotificationTone;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterKeyMetrics;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.provider.ProviderUnreachableException;
import io.github.kalpak44.workspacedai.api.workspace.ApiKeyRejectedException;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class ConnectorAlertsTest {
  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
  @Autowired ConnectorAlerts alerts;
  @Autowired ConnectorAlertScheduler scheduler;
  @Autowired AiConnectorRepository connectors;
  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired NotificationService notifications;
  @Autowired AuthProperties properties;
  @MockitoBean OpenRouterMetrics metrics;
  UUID owner;
  UUID other;

  @BeforeEach
  void prepare() {
    workspaces.deleteAll();
    connectors.deleteAll();
    owner = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com")).getId();
    other = users.saveAndFlush(TestUsers.newUser(UUID.randomUUID() + "@example.com")).getId();
  }

  @Test
  void namesThePersonalKeyAndEscalatesAtFiftyBelowTwentyAndZeroOnlyOnce() {
    var key = connector("Work", false);
    alerts.observe(key, usage("10", "5.01", null, null), NOW);
    assertThat(notifications.list(owner)).isEmpty();
    alerts.observe(key, usage("10", "5", null, null), NOW);
    alerts.observe(key, usage("10", "2", null, null), NOW);
    assertThat(notifications.list(owner))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.tone()).isEqualTo(NotificationTone.WARNING);
              assertThat(note.title()).contains("Work");
              assertThat(note.summary()).contains("$5.00", "$10.00");
            });
    alerts.observe(key, usage("10", "1.99", null, null), NOW);
    assertThat(notifications.list(owner)).hasSize(2);
    alerts.observe(key, usage("10", "0", null, null), NOW);
    alerts.observe(key, usage("10", "-0.01", null, null), NOW);
    var notes = notifications.list(owner);
    assertThat(notes)
        .hasSize(3)
        .allSatisfy(
            note -> {
              assertThat(note.title()).contains("Work");
              assertThat(note.action().path()).isEqualTo("/settings/ai-connectors");
              assertThat(note.body())
                  .contains("General settings", "Settings → Secrets")
                  .doesNotContain("key-secret");
            });
    assertThat(notes).filteredOn(note -> note.tone() == NotificationTone.ERROR).hasSize(2);
    assertThat(notifications.list(other)).isEmpty();
  }

  @Test
  void appliesTheSameThresholdsToTrialAndDoesNotSendSkippedWarnings() {
    var trial = connector("Trial", true);
    alerts.observe(trial, usage("1", "0.19", null, null), NOW);
    var notes = notifications.list(owner);
    assertThat(notes)
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.title()).isEqualTo("AI budget almost used up: Trial");
              assertThat(note.tone()).isEqualTo(NotificationTone.ERROR);
            });
    alerts.observe(trial, usage("1", "0.49", null, null), NOW);
    assertThat(notifications.list(owner)).hasSize(1);
  }

  @Test
  void concurrentMetricsRefreshesDoNotDuplicateAlerts() {
    var key = connector("Concurrent", false);
    var pending =
        List.of(
            CompletableFuture.runAsync(
                () -> alerts.observe(key, usage("1", "0.2", null, null), NOW)),
            CompletableFuture.runAsync(
                () -> alerts.observe(key, usage("1", "0.2", null, null), NOW)),
            CompletableFuture.runAsync(
                () -> alerts.observe(key, usage("1", "0.2", null, null), NOW)));
    pending.forEach(CompletableFuture::join);
    assertThat(notifications.list(owner)).hasSize(1);
  }

  @Test
  void reArmsWarningsInANewProviderBudgetWindow() {
    var key = connector("Monthly", false);
    alerts.observe(key, usage("10", "4", "monthly", null), NOW);
    alerts.observe(key, usage("10", "3", "monthly", null), NOW.plusSeconds(60));
    assertThat(notifications.list(owner)).hasSize(1);
    alerts.observe(key, usage("10", "4", "monthly", null), Instant.parse("2026-12-01T00:00:00Z"));
    assertThat(notifications.list(owner)).hasSize(2);
  }

  @Test
  void doesNotInventBudgetWarningsForUnlimitedOrUnknownUsage() {
    var key = connector("Unlimited", false);
    alerts.observe(key, usage(null, null, null, null), NOW);
    alerts.observe(key, usage("10", null, null, null), NOW);
    assertThat(notifications.list(owner)).isEmpty();
  }

  @Test
  void reportsConfirmedExpirationEvenForAnUnlimitedKey() {
    var key = connector("Expired", false);
    alerts.observe(key, usage(null, null, null, NOW.minusSeconds(1).toString()), NOW);
    alerts.observe(key, usage(null, null, null, NOW.minusSeconds(1).toString()), NOW);
    assertThat(notifications.list(owner))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.title()).isEqualTo("AI connector expired: Expired");
              assertThat(note.action().label()).isEqualTo("Connect your AI account");
            });
  }

  @Test
  void aRejectedKeyIsNotMislabelledAsAnExhaustedBudget() {
    var key = connector("Revoked", false);
    when(metrics.read(any())).thenThrow(new ApiKeyRejectedException(ModelProvider.OPENROUTER));
    alerts.check(key);
    alerts.check(key);
    assertThat(notifications.list(owner))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.title()).isEqualTo("AI connector needs attention: Revoked");
              assertThat(note.body()).contains("may have expired");
            });
  }

  @Test
  void aProviderFailureDoesNotSendExpiryAlertsAndDoesNotBlockOtherKeys() {
    connector("Unavailable", false);
    connector("Low", false);
    when(metrics.read("key-secret-Unavailable"))
        .thenThrow(new ProviderUnreachableException(ModelProvider.OPENROUTER, null));
    when(metrics.read("key-secret-Low")).thenReturn(usage("1", "0.1", null, null));
    scheduler.run();
    assertThat(notifications.list(owner))
        .singleElement()
        .satisfies(note -> assertThat(note.title()).contains("Low").doesNotContain("Unavailable"));
  }

  private UUID connector(String name, boolean trial) {
    var encrypted =
        new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors")
            .seal("key-secret-" + name);
    return connectors
        .saveAndFlush(
            trial
                ? AiConnectorEntity.trial(owner, encrypted, "hash")
                : new AiConnectorEntity(owner, name, "openrouter", encrypted))
        .getId();
  }

  private static OpenRouterKeyMetrics usage(
      String cap, String remaining, String reset, String expiry) {
    return new OpenRouterKeyMetrics(
        null,
        null,
        null,
        null,
        cap == null ? null : new BigDecimal(cap),
        remaining == null ? null : new BigDecimal(remaining),
        reset,
        expiry,
        false,
        null,
        false,
        null,
        null);
  }
}

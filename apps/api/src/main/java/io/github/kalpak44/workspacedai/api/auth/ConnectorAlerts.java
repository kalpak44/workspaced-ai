package io.github.kalpak44.workspacedai.api.auth;

import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterKeyMetrics;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterMetrics;
import io.github.kalpak44.workspacedai.api.workspace.ApiKeyRejectedException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider observations become per-owner, per-key alerts under a row lock, without holding a
 * transaction over HTTP.
 */
@Service
public class ConnectorAlerts {
  private static final Logger LOG = LoggerFactory.getLogger(ConnectorAlerts.class);
  private final AiConnectorRepository connectors;
  private final NotificationService notifications;
  private final OpenRouterMetrics metrics;
  private final SecretBox box;
  private final TransactionTemplate transaction;

  public ConnectorAlerts(
      AiConnectorRepository connectors,
      NotificationService notifications,
      OpenRouterMetrics metrics,
      AuthProperties properties,
      PlatformTransactionManager manager) {
    this.connectors = connectors;
    this.notifications = notifications;
    this.metrics = metrics;
    box = new SecretBox(properties.jwtSecret(), "workspaced-ai-connectors");
    transaction = new TransactionTemplate(manager);
  }

  public void observe(UUID id, OpenRouterKeyMetrics data) {
    try {
      observe(id, data, Instant.now());
    } catch (RuntimeException failure) {
      LOG.warn("could not record AI connector alerts for {}", id);
    }
  }

  void observe(UUID id, OpenRouterKeyMetrics data, Instant now) {
    transaction.executeWithoutResult(
        status ->
            connectors
                .findLockedById(id)
                .ifPresent(
                    connector -> {
                      connector.checkedAt(now);
                      var expiry = expiry(data.expiresAt());
                      connector.setObservedExpiresAt(expiry);
                      if (expiry != null && !expiry.isAfter(now)) {
                        expired(connector, expiry);
                        return;
                      }
                      var cap = data.limit();
                      var remaining = data.limitRemaining();
                      // No percentages are invented for an unlimited key or incomplete provider
                      // data.
                      if (cap == null || cap.signum() <= 0 || remaining == null) return;
                      var cycle = cycle(data.limitReset(), cap, now);
                      int level =
                          remaining.signum() <= 0
                              ? 3
                              : remaining
                                          .multiply(BigDecimal.valueOf(100))
                                          .compareTo(cap.multiply(BigDecimal.valueOf(20)))
                                      < 0
                                  ? 2
                                  : remaining
                                              .multiply(BigDecimal.valueOf(100))
                                              .compareTo(cap.multiply(BigDecimal.valueOf(50)))
                                          <= 0
                                      ? 1
                                      : 0;
                      var previous =
                          cycle.equals(connector.getBudgetNoticeCycle())
                              ? connector.getBudgetNoticeLevel()
                              : 0;
                      if (!cycle.equals(connector.getBudgetNoticeCycle()))
                        connector.budgetNotice(cycle, 0);
                      if (level <= previous) return;
                      notifications.sendOnce(
                          connector.getOwnerId(),
                          "connector-budget:" + id + ":" + cycle + ":" + level,
                          Notifications.connectorBudget(
                              connector.getName(), remaining.max(BigDecimal.ZERO), cap, level));
                      connector.budgetNotice(cycle, level);
                    }));
  }

  public void rejected(UUID id) {
    var now = Instant.now();
    transaction.executeWithoutResult(
        status ->
            connectors
                .findLockedById(id)
                .ifPresent(
                    connector -> {
                      connector.checkedAt(now);
                      var expiry = connector.getObservedExpiresAt();
                      if (expiry != null && !expiry.isAfter(now)) expired(connector, expiry);
                      else
                        notifications.sendOnce(
                            connector.getOwnerId(),
                            "connector-unavailable:" + id,
                            Notifications.connectorUnavailable(connector.getName()));
                    }));
  }

  private void expired(AiConnectorEntity connector, Instant expiry) {
    notifications.sendOnce(
        connector.getOwnerId(),
        "connector-expired:" + connector.getId() + ":" + expiry,
        Notifications.connectorExpired(connector.getName()));
  }

  /** Called by the bounded background sweep, even when the user has no page open. */
  public void check(UUID id) {
    var row = connectors.findById(id);
    if (row.isEmpty() || !row.get().getProvider().equals("openrouter")) return;
    try {
      var key = box.open(row.get().encryptedKey());
      if (key.isEmpty()) {
        checked(id);
        return;
      }
      observe(id, metrics.read(key.get()));
    } catch (ApiKeyRejectedException refused) {
      rejected(id);
    } catch (RuntimeException failure) {
      // Provider outages, rate limits and malformed data do not mean the user's budget expired.
      checked(id);
      LOG.warn("could not check AI connector {}", id);
    }
  }

  private void checked(UUID id) {
    transaction.executeWithoutResult(
        status -> connectors.findLockedById(id).ifPresent(row -> row.checkedAt(Instant.now())));
  }

  private static Instant expiry(String value) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException invalid) {
      return null;
    }
  }

  private static String cycle(String reset, BigDecimal cap, Instant now) {
    var date = now.atZone(ZoneOffset.UTC).toLocalDate();
    var period =
        switch (reset == null ? "" : reset) {
          case "daily" -> date.toString();
          case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
          case "monthly" -> YearMonth.from(date).toString();
          default -> "lifetime";
        };
    return period + ":" + cap.stripTrailingZeros().toEngineeringString();
  }
}

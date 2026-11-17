package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warns, once a day, about each workspace the balance will stop paying for within a week, since
 * {@link UsageCharger} then deletes it for good. All of a user's workspaces draw on one balance, so
 * how long it lasts is reckoned from what they cost together.
 */
@Component
public class FundsReminder {

  static final Duration WARN_BEFORE = Duration.ofDays(7);

  private static final Logger log = LoggerFactory.getLogger(FundsReminder.class);

  private final UsageRepository usage;
  private final BillingAccountRepository accounts;
  private final WorkspaceRepository workspaces;
  private final NotificationService notifications;
  private final TransactionTemplate transactions;

  FundsReminder(
      UsageRepository usage,
      BillingAccountRepository accounts,
      WorkspaceRepository workspaces,
      NotificationService notifications,
      TransactionTemplate transactions) {
    this.usage = usage;
    this.accounts = accounts;
    this.workspaces = workspaces;
    this.notifications = notifications;
    this.transactions = transactions;
  }

  // Hourly, so a balance that drops below a week is warned about the same day; the key sends
  // each reminder once a day however often this runs.
  @Scheduled(cron = "${workspaced.billing.funds-reminder-cron:0 0 * * * *}")
  @SchedulerLock(name = "billing-funds-reminder", lockAtMostFor = "PT30M")
  public void run() {
    remind(Instant.now());
  }

  void remind(Instant now) {
    var byUser = usage.findAll().stream().collect(Collectors.groupingBy(UsageEntity::getUserId));
    byUser.forEach(
        (user, rows) -> {
          try {
            remind(user, rows, now);
          } catch (RuntimeException e) {
            // The others are still reminded; this one is tried again on the next run.
            log.warn("could not remind user {} of their balance", user, e);
          }
        });
  }

  private void remind(UUID user, List<UsageEntity> rows, Instant now) {
    var hourly = rows.stream().mapToLong(UsageEntity::getHourlyMicros).sum();
    var balance = accounts.findById(user).map(BillingAccountEntity::getBalanceMicros).orElse(0L);
    var hoursLeft = BillingService.hoursCovered(balance, hourly);
    if (hoursLeft >= WARN_BEFORE.toHours()) {
      return;
    }
    var days = hoursLeft / 24;
    var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    transactions.executeWithoutResult(
        _ ->
            workspaces
                .findAllById(rows.stream().map(UsageEntity::getWorkspaceId).toList())
                .forEach(
                    workspace ->
                        notifications.sendOnce(
                            user,
                            "funds-running-out:" + workspace.getId() + ":" + today,
                            Notifications.fundsRunningOut(workspace.getName(), days))));
  }
}

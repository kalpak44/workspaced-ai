package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warns, once a day, about each workspace the balance will stop paying for within a week, since
 * {@link UsageCharger} then pauses it, and about each paused one until it is deleted. All of a
 * user's running workspaces draw on one balance, so how long it lasts is reckoned from what they
 * cost together. An admin, whose workspaces are free, is never warned.
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
  private final BillingService billing;
  private final BillingProperties properties;

  FundsReminder(
      UsageRepository usage,
      BillingAccountRepository accounts,
      WorkspaceRepository workspaces,
      NotificationService notifications,
      TransactionTemplate transactions,
      BillingService billing,
      BillingProperties properties) {
    this.usage = usage;
    this.accounts = accounts;
    this.workspaces = workspaces;
    this.notifications = notifications;
    this.transactions = transactions;
    this.billing = billing;
    this.properties = properties;
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
    if (billing.isFree(user)) {
      return;
    }
    var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var running = rows.stream().filter(row -> row.getPausedAt() == null).toList();
    var hourly = running.stream().mapToLong(UsageEntity::getHourlyMicros).sum();
    var balance = accounts.findById(user).map(BillingAccountEntity::getBalanceMicros).orElse(0L);
    var hoursLeft = hourly == 0 ? Long.MAX_VALUE : BillingService.hoursCovered(balance, hourly);
    var fee = billing.amount(billing.reactivationFeeMicros());
    if (hoursLeft < WARN_BEFORE.toHours()) {
      var days = hoursLeft / 24;
      send(
          user,
          running,
          workspace -> "funds-running-out:" + workspace.getId() + ":" + today,
          (workspace, row) ->
              Notifications.fundsRunningOut(
                  workspace.getName(), days, properties.pausedFor(), fee));
    }
    // The day it is paused, the pause itself said so.
    send(
        user,
        rows.stream()
            .filter(row -> row.getPausedAt() != null)
            .filter(row -> LocalDate.ofInstant(row.getPausedAt(), ZoneOffset.UTC).isBefore(today))
            .toList(),
        workspace -> "paused-workspace:" + workspace.getId() + ":" + today,
        (workspace, row) ->
            Notifications.pausedWorkspaceDeletion(
                workspace.getName(), row.getPausedAt().plus(properties.pausedFor()), now, fee));
  }

  private void send(
      UUID user,
      List<UsageEntity> rows,
      Function<WorkspaceEntity, String> key,
      BiFunction<WorkspaceEntity, UsageEntity, NotificationMessage> message) {
    if (rows.isEmpty()) {
      return;
    }
    var byWorkspace =
        rows.stream().collect(Collectors.toMap(UsageEntity::getWorkspaceId, row -> row));
    transactions.executeWithoutResult(
        _ ->
            workspaces
                .findAllById(byWorkspace.keySet())
                .forEach(
                    workspace ->
                        notifications.sendOnceAndMail(
                            user,
                            key.apply(workspace),
                            message.apply(workspace, byWorkspace.get(workspace.getId())))));
  }
}

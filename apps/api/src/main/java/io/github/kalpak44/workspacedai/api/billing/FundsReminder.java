package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.auth.UserRepository;
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
 *
 * <p>The warnings are mailed only at milestones, so the inbox is not filled daily: a week left,
 * three days, one day, and the last day before a paused workspace is deleted. A user who turned
 * balance reminders off is mailed only the last of these.
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
  private final UserRepository users;

  FundsReminder(
      UsageRepository usage,
      BillingAccountRepository accounts,
      WorkspaceRepository workspaces,
      NotificationService notifications,
      TransactionTemplate transactions,
      BillingService billing,
      BillingProperties properties,
      UserRepository users) {
    this.usage = usage;
    this.accounts = accounts;
    this.workspaces = workspaces;
    this.notifications = notifications;
    this.transactions = transactions;
    this.billing = billing;
    this.properties = properties;
    this.users = users;
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
      // In the daily key too, so a milestone crossed during the day is told of at once.
      var milestone = hoursLeft < 24 ? "1d" : hoursLeft < 72 ? "3d" : "7d";
      var mailed =
          users
              .findById(user)
              .map(u -> Boolean.TRUE.equals(u.getMailPreferences().balanceReminders()))
              .orElse(true);
      send(
          user,
          running,
          (workspace, row) ->
              "funds-running-out:" + workspace.getId() + ":" + today + ":" + milestone,
          (workspace, row) ->
              mailed ? "funds-running-out:" + workspace.getId() + ":" + milestone : null,
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
        (workspace, row) ->
            "paused-workspace:"
                + workspace.getId()
                + ":"
                + today
                + (lastDay(row, now) ? ":last-day" : ""),
        (workspace, row) ->
            lastDay(row, now) ? "paused-workspace:" + workspace.getId() + ":last-day" : null,
        (workspace, row) ->
            Notifications.pausedWorkspaceDeletion(workspace.getName(), deletesAt(row), now, fee));
  }

  private boolean lastDay(UsageEntity row, Instant now) {
    return deletesAt(row).isBefore(now.plus(Duration.ofDays(1)));
  }

  private Instant deletesAt(UsageEntity row) {
    return row.getPausedAt().plus(properties.pausedFor());
  }

  private void send(
      UUID user,
      List<UsageEntity> rows,
      BiFunction<WorkspaceEntity, UsageEntity, String> key,
      BiFunction<WorkspaceEntity, UsageEntity, String> mailKey,
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
                    workspace -> {
                      var row = byWorkspace.get(workspace.getId());
                      notifications.sendOnce(
                          user,
                          key.apply(workspace, row),
                          message.apply(workspace, row),
                          mailKey.apply(workspace, row));
                    }));
  }
}

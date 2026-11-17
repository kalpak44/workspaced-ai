package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pays for each charged workspace's hours as they start, the way a started hour is billed whole. A
 * workspace whose owner can no longer pay for the next one is paused: its VM is shut down and it is
 * not charged. Once the balance pays the reactivation fee and an hour, it starts again; if it does
 * not within {@link BillingProperties#pausedFor()}, it is deleted, since its VM keeps costing for
 * as long as it exists. An admin's workspace is never charged, paused or deleted for funds.
 */
@Component
public class UsageCharger {

  static final Duration HOUR = Duration.ofHours(1);

  private static final Logger log = LoggerFactory.getLogger(UsageCharger.class);

  /** How charging one workspace's next hour went. */
  enum Charge {
    CHARGED,
    /** Nothing is due, or the workspace is gone or paused. */
    NOTHING_DUE,
    /** The balance cannot pay for it; nothing was taken. */
    SHORT
  }

  private final UsageRepository usage;
  private final BillingAccountRepository accounts;
  private final Ledger ledger;
  private final TransactionTemplate transactions;
  private final WorkspaceService workspaces;
  private final WorkspaceRepository workspaceRows;
  private final NotificationService notifications;
  private final BillingService billing;
  private final BillingProperties properties;

  UsageCharger(
      UsageRepository usage,
      BillingAccountRepository accounts,
      Ledger ledger,
      TransactionTemplate transactions,
      WorkspaceService workspaces,
      WorkspaceRepository workspaceRows,
      NotificationService notifications,
      BillingService billing,
      BillingProperties properties) {
    this.usage = usage;
    this.accounts = accounts;
    this.ledger = ledger;
    this.transactions = transactions;
    this.workspaces = workspaces;
    this.workspaceRows = workspaceRows;
    this.notifications = notifications;
    this.billing = billing;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${workspaced.billing.charge-interval:PT5M}")
  @SchedulerLock(name = "billing-usage", lockAtMostFor = "PT30M")
  public void run() {
    var now = Instant.now();
    for (var due : usage.findAllByChargedUntilLessThanEqualAndPausedAtIsNull(now)) {
      try {
        charge(due.getWorkspaceId(), now);
      } catch (RuntimeException e) {
        // The rest still get charged; this one is tried again on the next run.
        log.warn("could not charge workspace {}", due.getWorkspaceId(), e);
      }
    }
    for (var paused : usage.findAllByPausedAtIsNotNull()) {
      try {
        settlePaused(paused.getWorkspaceId(), now);
      } catch (RuntimeException e) {
        log.warn("could not settle paused workspace {}", paused.getWorkspaceId(), e);
      }
    }
  }

  // Every hour that has started since the last run, oldest first.
  void charge(UUID workspace, Instant now) {
    var charge = chargeNextHour(workspace, now);
    while (charge == Charge.CHARGED) {
      charge = chargeNextHour(workspace, now);
    }
    if (charge == Charge.SHORT) {
      pause(workspace, now);
    }
  }

  /** Starts a paused workspace the balance can pay for again, or deletes one paused too long. */
  void settlePaused(UUID workspace, Instant now) {
    var row = usage.findById(workspace).orElse(null);
    if (row == null || row.getPausedAt() == null) {
      return;
    }
    var balance = accounts.findById(row.getUserId()).map(BillingAccountEntity::getBalanceMicros);
    if (billing.isFree(row.getUserId())
        || balance.orElse(0L) >= billing.reactivationFeeMicros() + row.getHourlyMicros()) {
      resume(workspace, now);
    } else if (!now.isBefore(row.getPausedAt().plus(properties.pausedFor()))) {
      log.info("deleting workspace {}: it stayed paused for want of funds", workspace);
      workspaces.deleteUnpaid(workspace);
    }
  }

  // The usage row stays locked while the hour is charged, so it is never charged twice.
  private Charge chargeNextHour(UUID workspace, Instant now) {
    return transactions.execute(
        _ -> {
          var row = usage.findLockedByWorkspaceId(workspace).orElse(null);
          if (row == null || row.getPausedAt() != null || row.getChargedUntil().isAfter(now)) {
            return Charge.NOTHING_DUE;
          }
          if (billing.isFree(row.getUserId())) {
            // Passed over unpaid, so a user who stops being an admin pays only from then on.
            row.chargedUntil(now.plus(HOUR));
            return Charge.NOTHING_DUE;
          }
          if (ledger.account(row.getUserId()).getBalanceMicros() < row.getHourlyMicros()) {
            return Charge.SHORT;
          }
          ledger.post(
              row.getUserId(),
              -row.getHourlyMicros(),
              Ledger.USAGE,
              workspace + "@" + row.getChargedUntil());
          row.chargedUntil(row.getChargedUntil().plus(HOUR));
          return Charge.CHARGED;
        });
  }

  // The VM is shut down before the row says so, so a shutdown that fails is tried again on the
  // next run.
  private void pause(UUID workspace, Instant now) {
    log.info("pausing workspace {}: the balance cannot pay its next hour", workspace);
    if (!workspaces.pause(workspace)) {
      return;
    }
    transactions.executeWithoutResult(
        _ ->
            usage
                .findLockedByWorkspaceId(workspace)
                .filter(row -> row.getPausedAt() == null)
                .ifPresent(
                    row -> {
                      row.pause(now);
                      notifyOwner(
                          row,
                          name ->
                              Notifications.workspacePaused(
                                  name,
                                  now.plus(properties.pausedFor()),
                                  billing.amount(billing.reactivationFeeMicros())));
                    }));
  }

  // The VM is started before it is charged for, so a start that fails costs nothing and is tried
  // again. Should the balance have dropped in between, it is shut down again.
  private void resume(UUID workspace, Instant now) {
    if (!workspaces.resume(workspace)) {
      return;
    }
    var resumed =
        Boolean.TRUE.equals(
            transactions.execute(
                _ -> {
                  var row = usage.findLockedByWorkspaceId(workspace).orElse(null);
                  if (row == null || row.getPausedAt() == null) {
                    return true;
                  }
                  var user = row.getUserId();
                  // An admin's workspace starts again free of the fee.
                  var free = billing.isFree(user);
                  var fee = free ? 0 : billing.reactivationFeeMicros();
                  if (!free
                      && ledger.account(user).getBalanceMicros() < fee + row.getHourlyMicros()) {
                    return false;
                  }
                  if (fee > 0) {
                    ledger.post(
                        user, -fee, Ledger.REACTIVATION, workspace + "@" + row.getPausedAt());
                  }
                  row.resume(now);
                  notifyOwner(
                      row,
                      name -> Notifications.workspaceResumed(workspace, name, billing.amount(fee)));
                  return true;
                }));
    if (resumed) {
      log.info("resumed workspace {}: the balance pays for it again", workspace);
    } else {
      workspaces.pause(workspace);
    }
  }

  private void notifyOwner(UsageEntity row, Function<String, NotificationMessage> message) {
    workspaceRows
        .findById(row.getWorkspaceId())
        .ifPresent(w -> notifications.send(row.getUserId(), message.apply(w.getName())));
  }
}

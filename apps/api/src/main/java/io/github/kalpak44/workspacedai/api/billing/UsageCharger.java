package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pays for each charged workspace's hours as they start, the way a started hour is billed whole,
 * and deletes a workspace whose owner can no longer pay for the next one: its VM keeps costing for
 * as long as it exists.
 */
@Component
public class UsageCharger {

  static final Duration HOUR = Duration.ofHours(1);

  private static final Logger log = LoggerFactory.getLogger(UsageCharger.class);

  /** How charging one workspace's next hour went. */
  enum Charge {
    CHARGED,
    /** Nothing is due, or the workspace is gone. */
    NOTHING_DUE,
    /** The balance cannot pay for it; nothing was taken. */
    SHORT
  }

  private final UsageRepository usage;
  private final Ledger ledger;
  private final TransactionTemplate transactions;
  private final WorkspaceService workspaces;

  UsageCharger(
      UsageRepository usage,
      Ledger ledger,
      TransactionTemplate transactions,
      WorkspaceService workspaces) {
    this.usage = usage;
    this.ledger = ledger;
    this.transactions = transactions;
    this.workspaces = workspaces;
  }

  @Scheduled(fixedDelayString = "${workspaced.billing.charge-interval:PT5M}")
  @SchedulerLock(name = "billing-usage", lockAtMostFor = "PT30M")
  public void run() {
    var now = Instant.now();
    for (var due : usage.findAllByChargedUntilLessThanEqual(now)) {
      try {
        charge(due.getWorkspaceId(), now);
      } catch (RuntimeException e) {
        // The rest still get charged; this one is tried again on the next run.
        log.warn("could not charge workspace {}", due.getWorkspaceId(), e);
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
      log.info("deleting workspace {}: the balance cannot pay its next hour", workspace);
      workspaces.delete(workspace);
    }
  }

  // The usage row stays locked while the hour is charged, so it is never charged twice.
  private Charge chargeNextHour(UUID workspace, Instant now) {
    return transactions.execute(
        _ -> {
          var row = usage.findLockedByWorkspaceId(workspace).orElse(null);
          if (row == null || row.getChargedUntil().isAfter(now)) {
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
}

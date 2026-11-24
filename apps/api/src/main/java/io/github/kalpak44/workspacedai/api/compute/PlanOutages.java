package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells every admin, in the app and by mail, when no plan has been available to create for a while,
 * and again once one is back. Hetzner sells server types out for hours at a time, and otherwise the
 * first to notice is a user who cannot sign up for the demo.
 */
@Component
public class PlanOutages {

  private static final Logger LOG = LoggerFactory.getLogger(PlanOutages.class);

  private record Open(long id, Instant startedAt, Instant alertedAt) {}

  private final VmPricing pricing;
  private final UserRepository users;
  private final NotificationService notifications;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Duration alertAfter;

  public PlanOutages(
      VmPricing pricing,
      UserRepository users,
      NotificationService notifications,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      @Value("${workspaced.plans.outage-alert-after:PT15M}") Duration alertAfter) {
    this.pricing = pricing;
    this.users = users;
    this.notifications = notifications;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.alertAfter = alertAfter;
  }

  @Scheduled(
      fixedDelayString = "${workspaced.plans.outage-check-interval:PT5M}",
      initialDelayString = "${workspaced.plans.outage-check-interval:PT5M}")
  @SchedulerLock(name = "plan-outages", lockAtMostFor = "PT5M")
  public void run() {
    check(Instant.now());
  }

  void check(Instant now) {
    boolean available;
    try {
      available = pricing.sizes(false).stream().anyMatch(VmSize::available);
    } catch (RuntimeException failure) {
      // An unreachable VM manager says nothing about Hetzner's stock, so it neither opens nor
      // closes an outage.
      LOG.warn("could not read the plans to check for an outage", failure);
      return;
    }
    transaction.executeWithoutResult(status -> record(available, now));
  }

  private void record(boolean available, Instant now) {
    var open =
        jdbc
            .query(
                "select id, started_at, alerted_at from plan_outage where ended_at is null"
                    + " for update",
                (row, _) ->
                    new Open(
                        row.getLong("id"),
                        row.getTimestamp("started_at").toInstant(),
                        row.getTimestamp("alerted_at") == null
                            ? null
                            : row.getTimestamp("alerted_at").toInstant()))
            .stream()
            .findFirst();
    if (available) {
      open.ifPresent(
          outage -> {
            jdbc.update(
                "update plan_outage set ended_at = ? where id = ?",
                Timestamp.from(now),
                outage.id());
            // Admins who were never told it started need not hear that it ended.
            if (outage.alertedAt() != null) {
              tellAdmins(Notifications.plansBack(Duration.between(outage.startedAt(), now)));
            }
          });
      return;
    }
    if (open.isEmpty()) {
      jdbc.update("insert into plan_outage (started_at) values (?)", Timestamp.from(now));
      return;
    }
    var outage = open.get();
    if (outage.alertedAt() == null && !outage.startedAt().plus(alertAfter).isAfter(now)) {
      jdbc.update(
          "update plan_outage set alerted_at = ? where id = ?", Timestamp.from(now), outage.id());
      tellAdmins(Notifications.plansSoldOut(outage.startedAt()));
    }
  }

  private void tellAdmins(NotificationMessage message) {
    users.findIdsByRole(Role.ADMIN).forEach(admin -> notifications.sendAndMail(admin, message));
  }
}

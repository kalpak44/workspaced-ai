package io.github.kalpak44.workspacedai.api.compute;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  private final VmPricing pricing;
  private final UserRepository users;
  private final NotificationService notifications;
  private final PlanOutageRepository outages;
  private final TransactionTemplate transaction;
  private final Duration alertAfter;
  private final PlanWaiters waiters;

  public PlanOutages(
      VmPricing pricing,
      UserRepository users,
      NotificationService notifications,
      PlanOutageRepository outages,
      PlatformTransactionManager manager,
      @Value("${workspaced.plans.outage-alert-after:PT15M}") Duration alertAfter,
      PlanWaiters waiters) {
    this.pricing = pricing;
    this.users = users;
    this.notifications = notifications;
    this.outages = outages;
    this.transaction = new TransactionTemplate(manager);
    this.alertAfter = alertAfter;
    this.waiters = waiters;
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
    boolean regular;
    try {
      var sizes = pricing.sizes(false);
      available = sizes.stream().anyMatch(VmSize::available);
      // The pricier stand-in is not what those waiting asked for.
      regular = sizes.stream().anyMatch(size -> size.recommended() && size.available());
    } catch (RuntimeException failure) {
      // An unreachable VM manager says nothing about Hetzner's stock, so it neither opens nor
      // closes an outage.
      LOG.warn("could not read the plans to check for an outage", failure);
      return;
    }
    transaction.executeWithoutResult(
        status -> {
          record(available, now);
          if (regular) {
            waiters.planIsBack();
          }
        });
  }

  private void record(boolean available, Instant now) {
    var open = outages.findFirstByEndedAtIsNull();
    if (available) {
      open.ifPresent(
          outage -> {
            outage.ended(now);
            // Admins who were never told it started need not hear that it ended.
            if (outage.isAlerted()) {
              tellAdmins(Notifications.plansBack(Duration.between(outage.getStartedAt(), now)));
            }
          });
      return;
    }
    if (open.isEmpty()) {
      outages.save(new PlanOutageEntity(now));
      return;
    }
    var outage = open.get();
    if (!outage.isAlerted() && !outage.getStartedAt().plus(alertAfter).isAfter(now)) {
      outage.alerted(now);
      tellAdmins(Notifications.plansSoldOut(outage.getStartedAt()));
    }
  }

  private void tellAdmins(NotificationMessage message) {
    users
        .findByRole(Role.ADMIN)
        .forEach(admin -> notifications.sendAndMail(admin.getId(), message));
  }
}

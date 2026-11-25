package io.github.kalpak44.workspacedai.api.lifecycle;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingService;
import io.github.kalpak44.workspacedai.api.billing.PaymentHistory;
import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.referral.ReferralProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tips sent at the moments people tend to drift away: a day after signing up without trying a
 * workspace, a day after the free trial without keeping it, a week after first paying, and a month
 * after the last workspace while money is left. Each goes to the app once, and to the inbox too
 * unless the user turned tips off. Admins get none.
 *
 * <p>Each is sent only within a few days of its moment, so accounts that passed it before this
 * existed are not all mailed at once, and a day or two of downtime loses none.
 */
@Component
public class LifecycleMail {

  static final Duration SIGNED_UP = Duration.ofDays(1);
  static final Duration AFTER_TRIAL = Duration.ofDays(1);
  static final Duration AFTER_FIRST_PAYMENT = Duration.ofDays(7);
  static final Duration UNUSED = Duration.ofDays(30);
  static final Duration WINDOW = Duration.ofDays(2);

  // Less than this left is not worth a mail.
  private static final long MIN_BALANCE_CENTS = 100;
  private static final long MICROS_PER_CENT = 10_000;

  private static final Logger log = LoggerFactory.getLogger(LifecycleMail.class);

  private final UserRepository users;
  private final PaymentHistory payments;
  private final BillingService billing;
  private final NotificationService notifications;
  private final ReferralProperties referrals;

  public LifecycleMail(
      UserRepository users,
      PaymentHistory payments,
      BillingService billing,
      NotificationService notifications,
      ReferralProperties referrals) {
    this.users = users;
    this.payments = payments;
    this.billing = billing;
    this.notifications = notifications;
    this.referrals = referrals;
  }

  @Scheduled(cron = "${workspaced.lifecycle-mail-cron:0 15 * * * *}")
  @SchedulerLock(name = "lifecycle-mail", lockAtMostFor = "PT30M")
  public void run() {
    send(Instant.now());
  }

  void send(Instant now) {
    var signedUp =
        users.findByRoleNotAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Role.ADMIN, now.minus(SIGNED_UP).minus(WINDOW), now.minus(SIGNED_UP));
    var neverPaid = unpaid(signedUp);
    signedUp.stream()
        .filter(user -> user.isDemoAvailable() && neverPaid.contains(user.getId()))
        .forEach(user -> tip(user, "start-trial", _ -> Notifications.startTrial()));

    var tried =
        users.findByRoleNotAndDemoStartedAtGreaterThanEqualAndDemoStartedAtLessThan(
            Role.ADMIN, now.minus(AFTER_TRIAL).minus(WINDOW), now.minus(AFTER_TRIAL));
    var notKept = unpaid(tried);
    tried.stream()
        .filter(user -> notKept.contains(user.getId()))
        .forEach(user -> tip(user, "after-trial", _ -> Notifications.afterTrial()));

    if (referrals.enabled()) {
      var paying =
          payments.firstPaidBetween(
              now.minus(AFTER_FIRST_PAYMENT).minus(WINDOW), now.minus(AFTER_FIRST_PAYMENT));
      users.findAllById(paying).stream()
          .filter(user -> user.getRole() != Role.ADMIN)
          .forEach(
              user ->
                  tip(
                      user,
                      "invite-friends",
                      _ ->
                          Notifications.inviteFriends(
                              percent(referrals.referee().percent()),
                              percent(referrals.referrer().percent()))));
    }

    var idle = payments.lastChargedBetween(now.minus(UNUSED).minus(WINDOW), now.minus(UNUSED));
    users.findAllById(idle).stream()
        .filter(user -> user.getRole() != Role.ADMIN)
        .forEach(
            user -> {
              var cents = payments.balanceCents(user.getId());
              if (cents >= MIN_BALANCE_CENTS) {
                tip(
                    user,
                    "balance-waiting",
                    _ -> Notifications.balanceWaiting(billing.amount(cents * MICROS_PER_CENT)));
              }
            });
  }

  private Collection<UUID> unpaid(Collection<UserEntity> users) {
    var ids = users.stream().map(UserEntity::getId).toList();
    var paid = payments.paid(ids);
    return ids.stream().filter(id -> !paid.contains(id)).toList();
  }

  // The key names the tip alone, so each is sent once for good, whichever run finds it first.
  private void tip(
      UserEntity user, String name, Function<UserEntity, NotificationMessage> message) {
    var key = "tip:" + name;
    try {
      notifications.sendOnce(
          user.getId(),
          key,
          message.apply(user),
          Boolean.TRUE.equals(user.getMailPreferences().tips()) ? key : null);
    } catch (RuntimeException e) {
      // The others are still sent; this one is tried again on the next run.
      log.warn("could not send tip {} to user {}", name, user.getId(), e);
    }
  }

  private static String percent(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString() + "%";
  }
}

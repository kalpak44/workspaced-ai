package io.github.kalpak44.workspacedai.api.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.MailPreferences;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingFixtures;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** Each tip goes out once, near its moment, to the app and, unless turned off, the inbox. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class LifecycleMailTest {

  @Autowired LifecycleMail lifecycle;
  @Autowired UserRepository users;
  @Autowired NotificationService notifications;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transactions;
  @Autowired ApplicationContext context;
  @MockitoBean Mailer mailer;

  private Instant now;

  @BeforeEach
  void setUp() {
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    when(mailer.send(any())).thenReturn("1");
  }

  @Test
  void invitesToTheTrialADayAfterSignUpOnceAndOnlyNearThatDay() {
    var fresh = user("fresh");
    signedUp(fresh, hoursAgo(30));
    var quiet = user("quiet");
    signedUp(quiet, hoursAgo(30));
    transactions.executeWithoutResult(
        _ ->
            users
                .findById(quiet)
                .orElseThrow()
                .setMailPreferences(new MailPreferences(true, false)));
    var old = user("old");
    signedUp(old, hoursAgo(24 * 5));
    var tried = user("tried");
    signedUp(tried, hoursAgo(30));
    demoStarted(tried, hoursAgo(29));

    lifecycle.send(now);
    lifecycle.send(now);

    assertThat(titles(fresh)).containsExactly("Your free trial is waiting");
    assertThat(mailedTo(fresh)).containsExactly("Your free trial is waiting");
    // Tips turned off: still in the app, never in the inbox.
    assertThat(titles(quiet)).containsExactly("Your free trial is waiting");
    assertThat(mailedTo(quiet)).isEmpty();
    assertThat(titles(old)).isEmpty();
    assertThat(titles(tried)).doesNotContain("Your free trial is waiting");
  }

  @Test
  void followsUpATrialThatWasNotKept() {
    var left = user("left");
    signedUp(left, hoursAgo(40));
    demoStarted(left, hoursAgo(30));
    var kept = user("kept");
    signedUp(kept, hoursAgo(40));
    demoStarted(kept, hoursAgo(30));
    paid(kept, hoursAgo(28));

    lifecycle.send(now);

    assertThat(titles(left)).containsExactly("What will you build next?");
    assertThat(titles(kept)).isEmpty();
  }

  @Test
  void asksAWeekAfterTheFirstPaymentToInviteFriends() {
    var payer = user("payer");
    paid(payer, hoursAgo(24 * 7 + 12));
    paid(payer, hoursAgo(2));
    var recent = user("recent");
    paid(recent, hoursAgo(24 * 3));

    lifecycle.send(now);

    assertThat(titles(payer)).containsExactly("Invite a friend, and you both get extra");
    assertThat(notifications.list(payer).getFirst().summary())
        .isEqualTo("Your friends get 10% extra. You get 10% of what they pay.");
    assertThat(titles(recent)).isEmpty();
  }

  @Test
  void remindsOfABalanceLeftUnusedForAMonth() {
    var saver = user("saver");
    BillingFixtures.credit(context, saver, 1250);
    charged(saver, hoursAgo(24 * 31));
    var spent = user("spent");
    BillingFixtures.credit(context, spent, 50);
    charged(spent, hoursAgo(24 * 31));
    var active = user("active");
    BillingFixtures.credit(context, active, 1250);
    charged(active, hoursAgo(24 * 31));
    charged(active, hoursAgo(1));

    lifecycle.send(now);

    assertThat(titles(saver)).containsExactly("You still have 12.50 EUR to use");
    assertThat(titles(spent)).isEmpty();
    assertThat(titles(active)).isEmpty();
  }

  private UUID user(String name) {
    return users.save(TestUsers.newUser(name + "-" + UUID.randomUUID() + "@example.com")).getId();
  }

  private Instant hoursAgo(long hours) {
    return now.minus(Duration.ofHours(hours));
  }

  // The database stamps both, so backdating them takes an update.
  private void signedUp(UUID user, Instant at) {
    update("UPDATE app_user SET created_at = ?1 WHERE id = ?2", at, user);
  }

  private void demoStarted(UUID user, Instant at) {
    update("UPDATE app_user SET demo_started_at = ?1 WHERE id = ?2", at, user);
  }

  private void update(String sql, Instant at, UUID user) {
    transactions.executeWithoutResult(
        _ ->
            entityManager
                .createNativeQuery(sql)
                .setParameter(1, at)
                .setParameter(2, user)
                .executeUpdate());
  }

  private void paid(UUID user, Instant at) {
    transactions.executeWithoutResult(_ -> BillingFixtures.paidTopUp(entityManager, user, at));
  }

  private void charged(UUID user, Instant at) {
    transactions.executeWithoutResult(_ -> BillingFixtures.usageCharge(entityManager, user, at));
  }

  private List<String> titles(UUID user) {
    return notifications.list(user).stream().map(Notification::title).toList();
  }

  /** The subjects mailed to the user, whose address a test account's subject carries. */
  private List<String> mailedTo(UUID user) {
    var subject = users.findById(user).orElseThrow().getIdpSubject();
    var address = subject.substring(subject.indexOf('|') + 1);
    return mockingDetails(mailer).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("send"))
        .map(invocation -> (Mailer.Mail) invocation.getArgument(0))
        .filter(mail -> mail.to().stream().anyMatch(to -> to.email().equals(address)))
        .map(Mailer.Mail::subject)
        .toList();
  }
}

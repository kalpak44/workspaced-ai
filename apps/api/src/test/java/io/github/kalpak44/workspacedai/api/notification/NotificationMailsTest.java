package io.github.kalpak44.workspacedai.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** As production runs it: the mail goes out on a thread of its own. */
@SpringBootTest(
    properties = {"workspaced.mail.background=true", "workspaced.mail.retry-interval=PT24H"})
@Import({TestDatabase.class, TestRuntime.class})
class NotificationMailsTest {

  @Autowired NotificationService notifications;
  @Autowired UserRepository users;
  @Autowired TransactionTemplate transactions;
  @MockitoBean Mailer mailer;
  @Autowired NotificationRepository repository;
  @Autowired NotificationMails delivery;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired io.github.kalpak44.workspacedai.api.auth.Auth0Users auth0;
  @Autowired io.github.kalpak44.workspacedai.api.auth.AuthProperties auth;
  @Autowired io.github.kalpak44.workspacedai.api.mail.MailService mail;
  @Autowired io.github.kalpak44.workspacedai.api.mail.MailProperties mailProperties;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  @Test
  void sendsTheNotificationWithoutWaitingForTheMailService() throws Exception {
    var email = "slow-mail-" + UUID.randomUUID() + "@example.com";
    var user = users.save(TestUsers.newUser(email)).getId();
    var release = new CountDownLatch(1);
    var sent = new AtomicBoolean();
    when(mailer.send(any()))
        .thenAnswer(
            _ -> {
              release.await(10, TimeUnit.SECONDS);
              sent.set(true);
              return "1";
            });

    transactions.executeWithoutResult(
        _ -> notifications.sendAndMail(user, Notifications.workspaceDeletedUnpaid("Docs")));

    // Back while the mail service is still busy; the notification is there already.
    assertThat(sent).isFalse();
    assertThat(notifications.list(user)).singleElement();
    release.countDown();
    verify(mailer, timeout(5000)).send(any());
    await(sent);
  }

  @Test
  void mailsNothingForAChangeRolledBack() {
    var user = users.save(TestUsers.newUser("rolled-back-" + UUID.randomUUID())).getId();

    transactions.executeWithoutResult(
        status -> {
          notifications.sendAndMail(user, Notifications.workspaceDeletedUnpaid("Docs"));
          status.setRollbackOnly();
        });

    assertThat(notifications.list(user)).isEmpty();
    verify(mailer, after(500).never()).send(any());
  }

  @Test
  void retriesPersistedMailAfterFailureOnANewSenderWithoutDuplicatingTheNotification()
      throws Exception {
    var user = users.save(TestUsers.newUser("retry-mail-" + UUID.randomUUID())).getId();
    var notification =
        new NotificationEntity(user, Notifications.workspaceDeletedUnpaid("Docs"), null);
    var intent = notification.requestMail();
    repository.save(notification);
    when(mailer.send(any()))
        .thenThrow(new IllegalStateException("temporary outage"))
        .thenReturn("sent");

    delivery.deliver(intent.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT mail_pending FROM notification WHERE id=?", Boolean.class, intent.id()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT mail_attempts FROM notification WHERE id=?", Integer.class, intent.id()))
        .isEqualTo(1);
    // Not retried before backoff, even by a different application instance.
    var restarted =
        new NotificationMails(users, auth0, mail, auth, mailProperties, repository, manager);
    try {
      restarted.deliver(intent.id());
      verify(mailer, org.mockito.Mockito.times(1)).send(any());
      makeDue(intent.id());
      restarted.retry();
      restarted.retry();
      verify(mailer, org.mockito.Mockito.times(2)).send(any());
      assertThat(
              jdbc.queryForObject(
                  "SELECT mail_pending FROM notification WHERE id=?", Boolean.class, intent.id()))
          .isFalse();
      assertThat(notifications.list(user)).hasSize(1);
    } finally {
      restarted.finish();
    }
  }

  @Test
  void recoversACommittedIntentWhoseCallbackNeverRanAndAnExpiredSenderLease() {
    var user = users.save(TestUsers.newUser("crashed-mail-" + UUID.randomUUID())).getId();
    var notification =
        new NotificationEntity(user, Notifications.workspaceDeletedUnpaid("Docs"), null);
    var intent = notification.requestMail();
    repository.save(notification);
    // Simulate a process dying after claiming its durable row, before sending.
    transactions.executeWithoutResult(
        _ ->
            repository
                .findLockedById(intent.id())
                .orElseThrow()
                .claimMail(java.time.Instant.now()));
    delivery.deliver(intent.id());
    verify(mailer, org.mockito.Mockito.never()).send(any());
    makeDue(intent.id());
    delivery.retry();
    verify(mailer, org.mockito.Mockito.times(1)).send(any());
    assertThat(
            jdbc.queryForObject(
                "SELECT mail_pending FROM notification WHERE id=?", Boolean.class, intent.id()))
        .isFalse();
  }

  @Test
  void onlyOneSenderCanDeliverWhileAnotherInstanceHoldsTheLease() throws Exception {
    var user = users.save(TestUsers.newUser("leased-mail-" + UUID.randomUUID())).getId();
    var notification =
        new NotificationEntity(user, Notifications.workspaceDeletedUnpaid("Docs"), null);
    var intent = notification.requestMail();
    repository.save(notification);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(mailer.send(any()))
        .thenAnswer(
            _ -> {
              entered.countDown();
              release.await(10, TimeUnit.SECONDS);
              return "sent";
            });
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> delivery.deliver(intent.id()));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        delivery.deliver(intent.id());
        verify(mailer, org.mockito.Mockito.times(1)).send(any());
      } finally {
        release.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void sendsCommittedMailEvenWhenItsAfterCommitCallbackWasLost() {
    var user = users.save(TestUsers.newUser("lost-callback-" + UUID.randomUUID())).getId();
    var notification =
        new NotificationEntity(user, Notifications.workspaceDeletedUnpaid("Docs"), null);
    notification.requestMail();
    repository.save(notification);
    delivery.retry();
    delivery.retry();
    verify(mailer, org.mockito.Mockito.times(1)).send(any());
    assertThat(notifications.list(user)).hasSize(1);
  }

  private void makeDue(UUID id) {
    jdbc.update(
        "UPDATE notification SET mail_next_attempt_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),
        id);
  }

  private static void await(AtomicBoolean done) throws InterruptedException {
    for (var i = 0; i < 50 && !done.get(); i++) {
      TimeUnit.MILLISECONDS.sleep(100);
    }
    assertThat(done).isTrue();
  }
}

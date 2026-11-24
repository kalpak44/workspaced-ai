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
@SpringBootTest(properties = "workspaced.mail.background=true")
@Import({TestDatabase.class, TestRuntime.class})
class NotificationMailsTest {

  @Autowired NotificationService notifications;
  @Autowired UserRepository users;
  @Autowired TransactionTemplate transactions;
  @MockitoBean Mailer mailer;

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

  private static void await(AtomicBoolean done) throws InterruptedException {
    for (var i = 0; i < 50 && !done.get(); i++) {
      TimeUnit.MILLISECONDS.sleep(100);
    }
    assertThat(done).isTrue();
  }
}

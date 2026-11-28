package io.github.kalpak44.workspacedai.api.notification;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.MailProperties;
import io.github.kalpak44.workspacedai.api.mail.MailService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable mail delivery. Intent commits with the notification; row leases serialize senders across
 * instances and retries recover failures and crashes. Delivery is at least once.
 */
@Component
class NotificationMails {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationMails.class);
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

  private final NotificationRepository notifications;
  private final TransactionTemplate transactions;
  private final UserRepository users;
  private final Auth0Users auth0Users;
  private final MailService mail;
  private final AuthProperties properties;
  private final boolean background;
  private final ExecutorService sender =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("notification-mail").factory());

  NotificationMails(
      UserRepository users,
      Auth0Users auth0Users,
      MailService mail,
      AuthProperties properties,
      MailProperties mailProperties,
      NotificationRepository notifications,
      PlatformTransactionManager manager) {
    this.notifications = notifications;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.users = users;
    this.auth0Users = auth0Users;
    this.mail = mail;
    this.properties = properties;
    this.background = mailProperties.background();
  }

  @TransactionalEventListener
  void mail(NotificationMail event) {
    if (background) {
      sender.execute(() -> deliver(event.id()));
    } else {
      deliver(event.id());
    }
  }

  @PreDestroy
  void finish() throws InterruptedException {
    sender.shutdown();
    if (!sender.awaitTermination(SHUTDOWN_WAIT.toSeconds(), TimeUnit.SECONDS)) {
      LOG.warn("{} notification mails were not sent before shutdown", sender.shutdownNow().size());
    }
  }

  /** A bounded sweep also recovers mail whose after-commit callback never ran. */
  @Scheduled(
      fixedDelayString = "${workspaced.mail.retry-interval:PT1M}",
      initialDelayString = "${workspaced.mail.retry-interval:PT1M}")
  void retry() {
    for (var notification :
        notifications
            .findTop50ByMailPendingTrueAndMailNextAttemptAtLessThanEqualOrderByMailNextAttemptAtAsc(
                Instant.now())) {
      deliver(notification.mail().id());
    }
  }

  void deliver(UUID id) {
    var event =
        transactions.execute(
            _ ->
                notifications
                    .findLockedById(id)
                    .filter(n -> n.claimMail(Instant.now()))
                    .map(NotificationEntity::mail)
                    .orElse(null));
    if (event == null) return;
    boolean sent = false;
    try {
      send(event);
      sent = true;
    } catch (RuntimeException e) {
      LOG.warn(
          "a notification to user {} could not be mailed: {}",
          event.user(),
          e.getClass().getName());
    }
    var success = sent;
    transactions.executeWithoutResult(
        _ ->
            notifications
                .findLockedById(id)
                .ifPresent(
                    n -> {
                      if (success) n.mailed();
                      else n.mailFailed();
                    }));
  }

  private void send(NotificationMail event) {
    var subject = users.findById(event.user()).map(UserEntity::getIdpSubject);
    if (subject.isEmpty()) return; // Account deletion also cascades its queued notifications.
    var email = auth0Users.email(subject.get());
    if (email.isBlank()) throw new IllegalStateException("recipient address unavailable");
    mail.notify(List.of(email), event.message().title(), text(event.message()));
  }

  private String text(NotificationMessage message) {
    var action = message.action();
    if (action == null) {
      return message.body();
    }
    var link = properties.appUrl().replaceAll("/+$", "") + action.path();
    return message.body() + "\n\n[" + action.label() + "](" + link + ")";
  }
}

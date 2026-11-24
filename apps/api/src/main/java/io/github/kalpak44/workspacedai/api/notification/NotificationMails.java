package io.github.kalpak44.workspacedai.api.notification;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.MailProperties;
import io.github.kalpak44.workspacedai.api.mail.MailService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails a notification to its user once it has committed, for one they should not miss by staying
 * out of the app. The address is asked of Auth0, where it lives. A mail that cannot go out is
 * logged; the notification itself was sent.
 *
 * <p>Mailed one at a time on a thread of its own, so a billing job that pauses many workspaces
 * waits on neither Auth0 nor Mailjet. What is still queued at shutdown is given a while to go out;
 * a mail lost to a crash is not sent again, though its notification is kept.
 */
@Component
class NotificationMails {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationMails.class);
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

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
      MailProperties mailProperties) {
    this.users = users;
    this.auth0Users = auth0Users;
    this.mail = mail;
    this.properties = properties;
    this.background = mailProperties.background();
  }

  @TransactionalEventListener
  void mail(NotificationMail event) {
    if (background) {
      sender.execute(() -> send(event));
    } else {
      send(event);
    }
  }

  @PreDestroy
  void finish() throws InterruptedException {
    sender.shutdown();
    if (!sender.awaitTermination(SHUTDOWN_WAIT.toSeconds(), TimeUnit.SECONDS)) {
      LOG.warn("{} notification mails were not sent before shutdown", sender.shutdownNow().size());
    }
  }

  private void send(NotificationMail event) {
    try {
      var subject = users.findById(event.user()).map(UserEntity::getIdpSubject);
      if (subject.isEmpty()) {
        return;
      }
      var email = auth0Users.email(subject.get());
      if (email.isBlank()) {
        return;
      }
      mail.notify(List.of(email), event.message().title(), text(event.message()));
    } catch (RuntimeException e) {
      // The exception alone: a mail service's message can repeat the recipient's address.
      LOG.warn(
          "a notification to user {} could not be mailed: {}",
          event.user(),
          e.getClass().getName());
    }
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

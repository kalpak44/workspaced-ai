package io.github.kalpak44.workspacedai.api.notification;

import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.AuthProperties;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.MailService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails a notification to its user once it has committed, for one they should not miss by staying
 * out of the app. The address is asked of Auth0, where it lives. A mail that cannot go out is
 * logged; the notification itself was sent.
 */
@Component
class NotificationMails {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationMails.class);

  private final UserRepository users;
  private final Auth0Users auth0Users;
  private final MailService mail;
  private final AuthProperties properties;

  NotificationMails(
      UserRepository users, Auth0Users auth0Users, MailService mail, AuthProperties properties) {
    this.users = users;
    this.auth0Users = auth0Users;
    this.mail = mail;
    this.properties = properties;
  }

  @TransactionalEventListener
  void mail(NotificationMail event) {
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

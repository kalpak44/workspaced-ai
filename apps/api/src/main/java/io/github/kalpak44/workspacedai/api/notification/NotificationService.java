package io.github.kalpak44.workspacedai.api.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends notifications and lists them for the user they were sent to. Sending joins the caller's
 * transaction, so a change rolled back is never announced.
 */
@Service
public class NotificationService {

  /** How long a milestone, once mailed, is not mailed again. */
  public static final Duration MAIL_ONCE_WITHIN = Duration.ofDays(7);

  private final NotificationRepository repository;
  private final ApplicationEventPublisher events;

  NotificationService(NotificationRepository repository, ApplicationEventPublisher events) {
    this.repository = repository;
    this.events = events;
  }

  @Transactional
  public void send(UUID user, NotificationMessage message) {
    repository.save(new NotificationEntity(user, message, null));
  }

  /** Sends it, and mails it to the user once the transaction commits. */
  @Transactional
  public void sendAndMail(UUID user, NotificationMessage message) {
    var notification = new NotificationEntity(user, message, null);
    var mail = notification.requestMail();
    repository.save(notification);
    events.publishEvent(mail);
  }

  /**
   * Sends it unless one with the same key was sent to the user before, for a reminder whose job
   * runs more often than it should repeat.
   */
  @Transactional
  public void sendOnce(UUID user, String key, NotificationMessage message) {
    sendOnce(user, key, message, null);
  }

  /**
   * As {@link #sendOnce(UUID, String, NotificationMessage)}, and mails what it sends unless the
   * user was mailed under the same mail key within {@link #MAIL_ONCE_WITHIN}: a reminder repeated
   * daily reaches the inbox only at the milestones the key names.
   *
   * @param mailKey null mails nothing
   */
  @Transactional
  public void sendOnce(UUID user, String key, NotificationMessage message, String mailKey) {
    if (repository.existsByUserIdAndDedupeKey(user, key)) {
      return;
    }
    var mail =
        mailKey != null
            && !repository.existsByUserIdAndMailKeyAndCreatedAtAfter(
                user, mailKey, Instant.now().minus(MAIL_ONCE_WITHIN));
    var notification = new NotificationEntity(user, message, key, mail ? mailKey : null);
    var event = mail ? notification.requestMail() : null;
    repository.save(notification);
    if (event != null) events.publishEvent(event);
  }

  /** The user's newest notifications, newest first. */
  @Transactional(readOnly = true)
  public List<Notification> list(UUID user) {
    return repository.findTop200ByUserIdOrderByCreatedAtDescIdDesc(user).stream()
        .map(NotificationEntity::view)
        .toList();
  }

  /** Ids that are not the user's are left alone. Answers the list as it now stands. */
  @Transactional
  public List<Notification> setRead(UUID user, List<UUID> ids, boolean read) {
    repository.findAllByUserIdAndIdIn(user, ids).forEach(n -> n.markRead(read));
    repository.flush();
    return list(user);
  }
}

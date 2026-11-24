package io.github.kalpak44.workspacedai.api.notification;

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
    send(user, message);
    events.publishEvent(new NotificationMail(user, message));
  }

  /**
   * Sends it unless one with the same key was sent to the user before, for a reminder whose job
   * runs more often than it should repeat.
   */
  @Transactional
  public void sendOnce(UUID user, String key, NotificationMessage message) {
    saveOnce(user, key, message);
  }

  /** As {@link #sendOnce}, and mails what it sends to the user once the transaction commits. */
  @Transactional
  public void sendOnceAndMail(UUID user, String key, NotificationMessage message) {
    if (saveOnce(user, key, message)) {
      events.publishEvent(new NotificationMail(user, message));
    }
  }

  private boolean saveOnce(UUID user, String key, NotificationMessage message) {
    if (repository.existsByUserIdAndDedupeKey(user, key)) {
      return false;
    }
    repository.save(new NotificationEntity(user, message, key));
    return true;
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

package io.github.kalpak44.workspacedai.api.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends notifications and lists them for the user they were sent to. Sending joins the caller's
 * transaction, so a change rolled back is never announced.
 */
@Service
public class NotificationService {

  private final NotificationRepository repository;

  NotificationService(NotificationRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void send(UUID user, NotificationMessage message) {
    repository.save(new NotificationEntity(user, message, null));
  }

  /**
   * Sends it unless one with the same key was sent to the user before, for a reminder whose job
   * runs more often than it should repeat.
   */
  @Transactional
  public void sendOnce(UUID user, String key, NotificationMessage message) {
    if (!repository.existsByUserIdAndDedupeKey(user, key)) {
      repository.save(new NotificationEntity(user, message, key));
    }
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

package io.github.kalpak44.workspacedai.api.notification;

import jakarta.persistence.EntityManager;

/** Notification rows for tests outside this package, which cannot reach its entity. */
public final class NotificationFixtures {

  private NotificationFixtures() {}

  /** Removes every user's notifications. Call inside a transaction. */
  public static void deleteAll(EntityManager entityManager) {
    var delete = entityManager.getCriteriaBuilder().createCriteriaDelete(NotificationEntity.class);
    delete.from(NotificationEntity.class);
    entityManager.createQuery(delete).executeUpdate();
  }
}

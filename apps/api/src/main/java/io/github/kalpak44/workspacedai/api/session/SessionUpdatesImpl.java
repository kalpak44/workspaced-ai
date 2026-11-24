package io.github.kalpak44.workspacedai.api.session;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

class SessionUpdatesImpl implements SessionUpdates {

  private final EntityManager entityManager;

  SessionUpdatesImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public int retitle(UUID id, String title) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(WorkspaceSessionEntity.class);
    var session = update.from(WorkspaceSessionEntity.class);
    update
        .set(session.get(WorkspaceSessionEntity_.title), title)
        .where(cb.equal(session.get(WorkspaceSessionEntity_.id), id));
    return entityManager.createQuery(update).executeUpdate();
  }

  @Override
  @Transactional
  public int reconcile(UUID id, boolean available, String title) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(WorkspaceSessionEntity.class);
    var session = update.from(WorkspaceSessionEntity.class);
    update
        .set(session.get(WorkspaceSessionEntity_.available), available)
        .set(session.get(WorkspaceSessionEntity_.title), title)
        .where(cb.equal(session.get(WorkspaceSessionEntity_.id), id));
    var updated = entityManager.createQuery(update).executeUpdate();
    entityManager.clear();
    return updated;
  }
}

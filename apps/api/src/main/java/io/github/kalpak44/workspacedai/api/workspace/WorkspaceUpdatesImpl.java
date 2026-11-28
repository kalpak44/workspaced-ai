package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.transaction.annotation.Transactional;

class WorkspaceUpdatesImpl implements WorkspaceUpdates {

  private final EntityManager entityManager;

  WorkspaceUpdatesImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public int claimDraft(UUID id) {
    return update(
        id,
        (update, workspace) ->
            update
                .set(workspace.get(WorkspaceEntity_.draft), false)
                .where(
                    update.getRestriction(),
                    entityManager
                        .getCriteriaBuilder()
                        .isTrue(workspace.get(WorkspaceEntity_.draft))));
  }

  @Override
  @Transactional
  public int returnToDraft(UUID id) {
    return update(
        id, (update, workspace) -> update.set(workspace.get(WorkspaceEntity_.draft), true));
  }

  @Override
  @Transactional
  public int deleteDraftsCreatedBefore(Instant before) {
    var cb = entityManager.getCriteriaBuilder();
    var delete = cb.createCriteriaDelete(WorkspaceEntity.class);
    var workspace = delete.from(WorkspaceEntity.class);
    delete.where(
        cb.isTrue(workspace.get(WorkspaceEntity_.draft)),
        cb.lessThan(workspace.get(WorkspaceEntity_.createdAt), before));
    return entityManager.createQuery(delete).executeUpdate();
  }

  @Override
  @Transactional
  public int updateModel(UUID id, String model) {
    return update(
        id, (update, workspace) -> update.set(workspace.get(WorkspaceEntity_.model), model));
  }

  @Override
  @Transactional
  public int markRestartRequired(UUID id) {
    return update(
        id,
        (update, workspace) ->
            update
                .set(workspace.get(WorkspaceEntity_.secretsPendingRestart), true)
                .set(
                    workspace.get(WorkspaceEntity_.configurationRevision),
                    entityManager
                        .getCriteriaBuilder()
                        .sum(workspace.get(WorkspaceEntity_.configurationRevision), 1L)));
  }

  @Override
  @Transactional
  public int updateFirewallAgentAllowed(UUID id, boolean allowed) {
    return update(
        id,
        (update, workspace) ->
            update.set(workspace.get(WorkspaceEntity_.firewallAgentAllowed), allowed));
  }

  /** One workspace's row, changed as {@code change} says; a later where narrows it further. */
  private int update(
      UUID id, BiConsumer<CriteriaUpdate<WorkspaceEntity>, Root<WorkspaceEntity>> change) {
    var cb = entityManager.getCriteriaBuilder();
    var update = cb.createCriteriaUpdate(WorkspaceEntity.class);
    var workspace = update.from(WorkspaceEntity.class);
    update.where(cb.equal(workspace.get(WorkspaceEntity_.id), id));
    change.accept(update, workspace);
    return entityManager.createQuery(update).executeUpdate();
  }
}

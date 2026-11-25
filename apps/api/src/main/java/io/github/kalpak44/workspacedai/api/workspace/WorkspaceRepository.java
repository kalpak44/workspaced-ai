package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface WorkspaceRepository
    extends JpaRepository<WorkspaceEntity, UUID>, WorkspaceUpdates {

  List<WorkspaceEntity> findAllByOwnerIdAndAiConnectorId(UUID ownerId, UUID connectorId);

  /** Held to the end of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkspaceEntity> findLockedById(UUID id);

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);

  /** As above, without the drafts, which nobody but the dialog that made them knows of. */
  List<WorkspaceEntity> findAllByOwnerIdAndDraftFalseOrderByCreatedAtAscIdAsc(UUID ownerId);

  /** The account's demo that has not been launched yet, which a second tour picks up again. */
  Optional<WorkspaceEntity> findFirstByOwnerIdAndModelProviderAndDemoTrueAndDraftTrue(
      UUID ownerId, String modelProvider);

  /** Demos with a VM, which count against how many may run at once. */
  long countByDemoTrueAndDraftFalse();

  /** Demos whose time has run out, and those that run out soon, oldest first. */
  List<WorkspaceEntity> findAllByDemoTrueAndExpiresAtBeforeOrderByExpiresAtAsc(Instant before);

  List<IdOnly> findAllProjectedBy();

  /** Every workspace that has, or is getting, a VM. */
  List<WorkspaceEntity> findAllByDraftFalse();

  List<IdOnly> findByFirewallAgentAllowedTrue();

  List<IdOnly> findBySessionTtlEnabledTrue();

  Optional<OwnerOnly> findOwnerById(UUID id);

  default List<UUID> findAllIds() {
    return ids(findAllProjectedBy());
  }

  default List<UUID> findFirewallAgentAllowedIds() {
    return ids(findByFirewallAgentAllowedTrue());
  }

  default List<UUID> findTtlEnabledIds() {
    return ids(findBySessionTtlEnabledTrue());
  }

  /** The workspace's owner, read without loading the workspace. */
  default Optional<UUID> findOwnerIdById(UUID id) {
    return findOwnerById(id).map(OwnerOnly::getOwnerId);
  }

  private static List<UUID> ids(List<IdOnly> rows) {
    return rows.stream().map(IdOnly::getId).toList();
  }

  interface IdOnly {
    UUID getId();
  }

  interface OwnerOnly {
    UUID getOwnerId();
  }
}

package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository
    extends JpaRepository<WorkspaceEntity, UUID>, WorkspaceUpdates {

  List<WorkspaceEntity> findAllByOwnerIdAndAiConnectorId(UUID ownerId, UUID connectorId);

  /** Held to the end of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkspaceEntity> findLockedById(UUID id);

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);

  /**
   * Those the user owns or was invited to, oldest first, without the drafts, which nobody but the
   * dialog that made them knows of.
   */
  @Query(
      value =
          """
          SELECT w.* FROM workspace w
          WHERE NOT w.draft
            AND (w.owner_id = :user
              OR EXISTS (SELECT 1 FROM workspace_collaborator c
                         WHERE c.workspace_id = w.id AND c.user_id = :user))
          ORDER BY w.created_at, w.id
          """,
      nativeQuery = true)
  List<WorkspaceEntity> findAllReachableBy(@Param("user") UUID user);

  /** Whether the user owns or collaborates on it; empty when no workspace has that id. */
  @Query(
      value =
          """
          SELECT w.owner_id = :user OR EXISTS (SELECT 1 FROM workspace_collaborator c
                                               WHERE c.workspace_id = w.id AND c.user_id = :user)
          FROM workspace w WHERE w.id = :id
          """,
      nativeQuery = true)
  Optional<Boolean> findAccess(@Param("id") UUID id, @Param("user") UUID user);

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

  /** What a collaborator calls each workspace they are on. */
  interface CollaboratorName {
    UUID getWorkspaceId();

    String getName();
  }

  @Query(
      value =
          """
          SELECT c.workspace_id AS workspaceId, c.name AS name
          FROM workspace_collaborator c WHERE c.user_id = :user
          """,
      nativeQuery = true)
  List<CollaboratorName> findCollaboratorNames(@Param("user") UUID user);

  /** Renames it for one collaborator alone; 0 when they are not on it. */
  @Modifying
  @Query(
      value =
          """
          UPDATE workspace_collaborator SET name = :name
          WHERE workspace_id = :id AND user_id = :user
          """,
      nativeQuery = true)
  int renameForCollaborator(
      @Param("id") UUID id, @Param("user") UUID user, @Param("name") String name);

  /** Its owner and its collaborators; empty when no workspace has that id. */
  @Query(
      value =
          """
          SELECT w.owner_id FROM workspace w WHERE w.id = :id
          UNION
          SELECT c.user_id FROM workspace_collaborator c WHERE c.workspace_id = :id
          """,
      nativeQuery = true)
  List<UUID> findMemberIds(@Param("id") UUID id);

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

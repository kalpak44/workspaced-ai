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
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

  @Query("select w.id from WorkspaceEntity w")
  List<UUID> findAllIds();

  @Query("select w.id from WorkspaceEntity w where w.firewallAgentAllowed = true")
  List<UUID> findFirewallAgentAllowedIds();

  @Query("select w.id from WorkspaceEntity w where w.sessionTtlEnabled = true")
  List<UUID> findTtlEnabledIds();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from WorkspaceEntity w where w.id = :id")
  Optional<WorkspaceEntity> lockById(UUID id);

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);

  /** As above, without the drafts, which nobody but the dialog that made them knows of. */
  List<WorkspaceEntity> findAllByOwnerIdAndDraftFalseOrderByCreatedAtAscIdAsc(UUID ownerId);

  /**
   * Takes a draft for launching; 0 when it is not one, or another launch took it first. Its own
   * transaction, committed before the VM is asked for.
   */
  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.draft = false where w.id = :id and w.draft = true")
  int claimDraft(@Param("id") UUID id);

  /** Hands a draft back after its launch failed, so it can be launched again. */
  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.draft = true where w.id = :id")
  int returnToDraft(@Param("id") UUID id);

  /** The database cascades the delete to whatever a draft collected. */
  @Transactional
  @Modifying
  @Query("delete from WorkspaceEntity w where w.draft = true and w.createdAt < :before")
  int deleteDraftsCreatedBefore(@Param("before") Instant before);

  /** Its own transaction, for a caller that must not hold one while it asks the harness. */
  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.model = :model where w.id = :id")
  int updateModel(@Param("id") UUID id, @Param("model") String model);

  /** Its own transaction, for a caller that has just changed what the harness reads at start. */
  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.secretsPendingRestart = true where w.id = :id")
  int markRestartRequired(@Param("id") UUID id);

  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.firewallAgentAllowed = :allowed where w.id = :id")
  int updateFirewallAgentAllowed(@Param("id") UUID id, @Param("allowed") boolean allowed);

  @Query("select w.id from WorkspaceEntity w where w.ownerId = :ownerId")
  List<UUID> findIdsByOwnerId(UUID ownerId);

  @Query("select w.ownerId from WorkspaceEntity w where w.id = :id")
  Optional<UUID> findOwnerIdById(UUID id);
}

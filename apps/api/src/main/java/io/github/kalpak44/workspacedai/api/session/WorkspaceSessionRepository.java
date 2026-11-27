package io.github.kalpak44.workspacedai.api.session;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceSessionRepository
    extends JpaRepository<WorkspaceSessionEntity, UUID>, SessionUpdates {

  /** Oldest first; the id breaks ties between sessions created in the same instant. */
  List<WorkspaceSessionEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

  /** As above, only those the user sees: the ones they started, and the tasks' runs. */
  @Query(
      """
      select s from WorkspaceSessionEntity s
      where s.workspaceId = :workspace and (s.task is not null or s.userId = :user)
      order by s.createdAt, s.id
      """)
  List<WorkspaceSessionEntity> findAllVisible(
      @Param("workspace") UUID workspaceId, @Param("user") UUID userId);

  /** Whether the user sees it; empty when the workspace has no session with that id. */
  @Query(
      value =
          """
          SELECT s.task IS NOT NULL OR s.user_id IS NOT DISTINCT FROM :user
          FROM workspace_session s WHERE s.id = :id AND s.workspace_id = :workspace
          """,
      nativeQuery = true)
  Optional<Boolean> findVisibility(
      @Param("workspace") UUID workspaceId, @Param("id") UUID id, @Param("user") UUID userId);

  Optional<WorkspaceSessionEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

  /** Held to the end of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<WorkspaceSessionEntity> findLockedByIdAndWorkspaceId(UUID id, UUID workspaceId);

  Optional<WorkspaceOnly> findWorkspaceById(UUID id);

  /** The workspace the session belongs to, read without loading the session. */
  default Optional<UUID> findWorkspaceId(UUID id) {
    return findWorkspaceById(id).map(WorkspaceOnly::getWorkspaceId);
  }

  interface WorkspaceOnly {
    UUID getWorkspaceId();
  }
}

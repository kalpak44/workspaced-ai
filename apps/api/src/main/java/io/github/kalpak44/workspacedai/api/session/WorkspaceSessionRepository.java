package io.github.kalpak44.workspacedai.api.session;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceSessionRepository extends JpaRepository<WorkspaceSessionEntity, UUID> {

  /** Oldest first; the id breaks ties between sessions created in the same instant. */
  List<WorkspaceSessionEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

  Optional<WorkspaceSessionEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

  @Query("select s.workspaceId from WorkspaceSessionEntity s where s.id = :id")
  Optional<UUID> findWorkspaceId(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from WorkspaceSessionEntity s where s.id = :id and s.workspaceId = :workspaceId")
  Optional<WorkspaceSessionEntity> lockByIdAndWorkspaceId(UUID id, UUID workspaceId);

  /** Its own transaction: it runs on a stream's reader thread, outside any request. */
  @Transactional
  @Modifying
  @Query("update WorkspaceSessionEntity s set s.title = :title where s.id = :id")
  int retitle(@Param("id") UUID id, @Param("title") String title);

  /**
   * A row deleted meanwhile is skipped rather than failing, as a dirty entity's update would. It
   * clears the loaded entities, so a read that follows sees the change.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "update WorkspaceSessionEntity s set s.available = :available, s.title = :title"
          + " where s.id = :id")
  int reconcile(
      @Param("id") UUID id, @Param("available") boolean available, @Param("title") String title);
}

package io.github.kalpak44.workspacedai.api.session;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface WorkspaceSessionRepository
    extends JpaRepository<WorkspaceSessionEntity, UUID>, SessionUpdates {

  /** Oldest first; the id breaks ties between sessions created in the same instant. */
  List<WorkspaceSessionEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

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

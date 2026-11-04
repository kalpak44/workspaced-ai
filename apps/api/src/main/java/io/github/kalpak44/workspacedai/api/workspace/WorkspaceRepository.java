package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

  @Query("select w.id from WorkspaceEntity w where w.sessionTtlEnabled = true")
  List<UUID> findTtlEnabledIds();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from WorkspaceEntity w where w.id = :id")
  Optional<WorkspaceEntity> lockById(UUID id);

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOrderByCreatedAtAscIdAsc();
}

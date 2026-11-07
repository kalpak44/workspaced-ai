package io.github.kalpak44.workspacedai.api.workspace;

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

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

  @Query("select w.id from WorkspaceEntity w")
  List<UUID> findAllIds();

  @Query("select w.id from WorkspaceEntity w where w.sessionTtlEnabled = true")
  List<UUID> findTtlEnabledIds();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from WorkspaceEntity w where w.id = :id")
  Optional<WorkspaceEntity> lockById(UUID id);

  /** Oldest first; the id breaks ties between workspaces created in the same instant. */
  List<WorkspaceEntity> findAllByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);

  /** Its own transaction, for a caller that must not hold one while it asks the harness. */
  @Transactional
  @Modifying
  @Query("update WorkspaceEntity w set w.model = :model where w.id = :id")
  int updateModel(@Param("id") UUID id, @Param("model") String model);

  @Query("select w.id from WorkspaceEntity w where w.ownerId = :ownerId")
  List<UUID> findIdsByOwnerId(UUID ownerId);

  @Query("select w.ownerId from WorkspaceEntity w where w.id = :id")
  Optional<UUID> findOwnerIdById(UUID id);
}

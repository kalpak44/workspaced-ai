package io.github.kalpak44.workspacedai.agenthub.agent;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

  /** Held until the transaction ends; waits for, and then sees, a concurrent writer's commit. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AgentEntity> findLockedByVmId(UUID vmId);
}

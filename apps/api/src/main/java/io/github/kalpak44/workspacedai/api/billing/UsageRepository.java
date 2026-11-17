package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface UsageRepository extends JpaRepository<UsageEntity, UUID> {

  List<UsageEntity> findAllByUserId(UUID userId);

  /** The workspaces whose next hour has started and is not paid for yet. */
  List<UsageEntity> findAllByChargedUntilLessThanEqual(Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UsageEntity> findLockedByWorkspaceId(UUID workspaceId);
}

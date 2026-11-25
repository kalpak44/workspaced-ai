package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UsageRepository extends JpaRepository<UsageEntity, UUID> {

  List<UsageEntity> findAllByUserId(UUID userId);

  /** The running workspaces whose next hour has started and is not paid for yet. */
  List<UsageEntity> findAllByChargedUntilLessThanEqualAndPausedAtIsNull(Instant now);

  List<UsageEntity> findAllByPausedAtIsNotNull();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<UsageEntity> findLockedByWorkspaceId(UUID workspaceId);

  /** 1 when this started the charge; a row already there keeps the hours it has paid for. */
  @Modifying
  @Query(
      value =
          "INSERT INTO billing_usage (workspace_id, user_id, hourly_micros, charged_until)"
              + " VALUES (:workspace, :user, :hourly, :from) ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(UUID workspace, UUID user, long hourly, Instant from);
}

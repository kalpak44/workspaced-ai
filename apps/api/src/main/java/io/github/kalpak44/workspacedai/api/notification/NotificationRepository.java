package io.github.kalpak44.workspacedai.api.notification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<NotificationEntity> findLockedById(UUID id);

  List<NotificationEntity>
      findTop50ByMailPendingTrueAndMailNextAttemptAtLessThanEqualOrderByMailNextAttemptAtAsc(
          Instant now);

  /** Newest first; the id breaks ties between notifications sent in the same instant. */
  List<NotificationEntity> findTop200ByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

  List<NotificationEntity> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

  boolean existsByUserIdAndDedupeKey(UUID userId, String dedupeKey);

  boolean existsByUserIdAndMailKeyAndCreatedAtAfter(UUID userId, String mailKey, Instant after);
}

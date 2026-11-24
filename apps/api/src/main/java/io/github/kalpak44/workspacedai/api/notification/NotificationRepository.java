package io.github.kalpak44.workspacedai.api.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

  /** Newest first; the id breaks ties between notifications sent in the same instant. */
  List<NotificationEntity> findTop200ByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

  List<NotificationEntity> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

  boolean existsByUserIdAndDedupeKey(UUID userId, String dedupeKey);

  boolean existsByUserIdAndMailKeyAndCreatedAtAfter(UUID userId, String mailKey, Instant after);
}

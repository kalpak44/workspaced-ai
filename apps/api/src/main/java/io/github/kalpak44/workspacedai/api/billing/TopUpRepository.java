package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface TopUpRepository extends JpaRepository<TopUpEntity, UUID> {

  /** Held to the end of the transaction, so a top-up reported twice at once is credited once. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<TopUpEntity> findLockedById(UUID id);

  Optional<TopUpEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<TopUpEntity> findByCheckoutSessionId(String checkoutSessionId);

  List<TopUpEntity> findAllByUserIdAndStatusNotOrderByCreatedAtDesc(
      UUID userId, TopUpStatus status);

  void deleteByIdAndStatus(UUID id, TopUpStatus status);
}

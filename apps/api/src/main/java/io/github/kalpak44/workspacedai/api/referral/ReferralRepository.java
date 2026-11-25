package io.github.kalpak44.workspacedai.api.referral;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface ReferralRepository extends JpaRepository<ReferralEntity, UUID> {

  /** Held to the end of the transaction, so two top-ups paid at once cannot both pass a cap. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ReferralEntity> findLockedByRefereeId(UUID refereeId);

  List<ReferralEntity> findByReferrerIdOrderBySignedUpAtDesc(UUID referrerId);
}

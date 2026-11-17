package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface BillingAccountRepository extends JpaRepository<BillingAccountEntity, UUID> {

  /** Held to the end of the transaction, so two changes to one balance never interleave. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BillingAccountEntity> findLockedByUserId(UUID userId);

  Optional<BillingAccountEntity> findByStripeCustomerId(String stripeCustomerId);

  List<BillingAccountEntity> findAllByAutoTopUpEnabledTrue();
}

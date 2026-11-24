package io.github.kalpak44.workspacedai.api.compute;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface PlanOutageRepository extends JpaRepository<PlanOutageEntity, Long> {

  /** The outage under way, held to the end of the transaction; there is never more than one. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PlanOutageEntity> findFirstByEndedAtIsNull();
}

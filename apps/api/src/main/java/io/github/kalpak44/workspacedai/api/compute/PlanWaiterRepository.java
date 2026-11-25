package io.github.kalpak44.workspacedai.api.compute;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PlanWaiterRepository extends JpaRepository<PlanWaiterEntity, UUID> {

  /** Asking twice, or twice at once, leaves one request. */
  @Modifying
  @Query(
      value = "INSERT INTO plan_waiter (user_id) VALUES (:user) ON CONFLICT DO NOTHING",
      nativeQuery = true)
  void insertIfAbsent(UUID user);
}

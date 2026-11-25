package io.github.kalpak44.workspacedai.api.compute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A user waiting to be emailed once a plan can be created again. */
@Entity
@Table(name = "plan_waiter")
class PlanWaiterEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  protected PlanWaiterEntity() {
    // For JPA.
  }

  PlanWaiterEntity(UUID userId) {
    this.userId = userId;
  }

  UUID getUserId() {
    return userId;
  }
}

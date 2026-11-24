package io.github.kalpak44.workspacedai.api.compute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A stretch of time no plan could be created; open while endedAt is null. */
@Entity
@Table(name = "plan_outage")
class PlanOutageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "alerted_at")
  private Instant alertedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  protected PlanOutageEntity() {
    // For JPA.
  }

  PlanOutageEntity(Instant startedAt) {
    this.startedAt = startedAt;
  }

  Instant getStartedAt() {
    return startedAt;
  }

  boolean isAlerted() {
    return alertedAt != null;
  }

  void alerted(Instant at) {
    alertedAt = at;
  }

  void ended(Instant at) {
    endedAt = at;
  }
}

package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * What a workspace costs its owner an hour, fixed when it was created, the end of the last hour
 * charged for, and since when it is paused for want of funds. Gone with the workspace, so a deleted
 * one stops costing at once.
 */
@Entity
@Table(name = "billing_usage")
class UsageEntity {

  @Id
  @Column(name = "workspace_id")
  private UUID workspaceId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "hourly_micros", nullable = false)
  private long hourlyMicros;

  @Column(name = "charged_until", nullable = false)
  private Instant chargedUntil;

  @Column(name = "paused_at")
  private Instant pausedAt;

  protected UsageEntity() {}

  UsageEntity(UUID workspaceId, UUID userId, long hourlyMicros, Instant from) {
    this.workspaceId = workspaceId;
    this.userId = userId;
    this.hourlyMicros = hourlyMicros;
    this.chargedUntil = from;
  }

  UUID getWorkspaceId() {
    return workspaceId;
  }

  UUID getUserId() {
    return userId;
  }

  long getHourlyMicros() {
    return hourlyMicros;
  }

  Instant getChargedUntil() {
    return chargedUntil;
  }

  void chargedUntil(Instant until) {
    this.chargedUntil = until;
  }

  /** Null while it runs. */
  Instant getPausedAt() {
    return pausedAt;
  }

  void pause(Instant at) {
    this.pausedAt = at;
  }

  /** Charged again from now on; the hours it was paused cost nothing. */
  void resume(Instant now) {
    this.pausedAt = null;
    this.chargedUntil = now;
  }
}

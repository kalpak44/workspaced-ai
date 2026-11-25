package io.github.kalpak44.workspacedai.api.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Who invited a user, with what each side has been credited for it so far. */
@Entity
@Table(name = "referral")
class ReferralEntity {

  @Id
  @Column(name = "referee_id")
  private UUID refereeId;

  @Column(name = "referrer_id", nullable = false, updatable = false)
  private UUID referrerId;

  @Column(name = "signed_up_at", nullable = false, updatable = false)
  private Instant signedUpAt;

  @Column(name = "first_paid_at")
  private Instant firstPaidAt;

  @Column(name = "bonus_micros", nullable = false)
  private long bonusMicros;

  @Column(name = "reward_micros", nullable = false)
  private long rewardMicros;

  protected ReferralEntity() {
    // For JPA.
  }

  ReferralEntity(UUID refereeId, UUID referrerId) {
    this.refereeId = refereeId;
    this.referrerId = referrerId;
    // Postgres keeps microseconds, so the value read back later equals this one.
    this.signedUpAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  void paid(Instant at) {
    if (firstPaidAt == null) {
      firstPaidAt = at;
    }
  }

  void addBonus(long micros) {
    bonusMicros += micros;
  }

  void addReward(long micros) {
    rewardMicros += micros;
  }

  UUID getRefereeId() {
    return refereeId;
  }

  UUID getReferrerId() {
    return referrerId;
  }

  Instant getSignedUpAt() {
    return signedUpAt;
  }

  Instant getFirstPaidAt() {
    return firstPaidAt;
  }

  long getBonusMicros() {
    return bonusMicros;
  }

  long getRewardMicros() {
    return rewardMicros;
  }
}

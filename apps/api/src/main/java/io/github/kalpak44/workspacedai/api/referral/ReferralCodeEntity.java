package io.github.kalpak44.workspacedai.api.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A user's invitation code, written once through {@link ReferralCodeRepository#insertIfAbsent}. */
@Entity
@Table(name = "referral_code")
class ReferralCodeEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(nullable = false, length = 12, updatable = false)
  private String code;

  protected ReferralCodeEntity() {
    // For JPA.
  }

  UUID getUserId() {
    return userId;
  }

  String getCode() {
    return code;
  }
}

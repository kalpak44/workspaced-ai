package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** The account an inbox had its free trial with, by a keyed hash of the address. */
@Entity
@Table(name = "trial_email")
class TrialEmailEntity {

  @Id
  @Column(name = "email_hash", length = 64)
  private String emailHash;

  // Null once that account is deleted; the address still had its trial.
  @Column(name = "user_id")
  private UUID userId;

  protected TrialEmailEntity() {
    // For JPA.
  }

  UUID getUserId() {
    return userId;
  }
}

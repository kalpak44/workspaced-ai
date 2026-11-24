package io.github.kalpak44.workspacedai.agenthub.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of the API's {@code enrollment_token} table, at most one per VM. */
@Entity
@Table(name = "enrollment_token")
public class EnrollmentTokenEntity {

  @Id
  @Column(name = "vm_id")
  private UUID vmId;

  @Column(name = "token_hash", nullable = false)
  private byte[] tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected EnrollmentTokenEntity() {
    // For JPA.
  }

  EnrollmentTokenEntity(UUID vmId) {
    this.vmId = vmId;
  }

  public byte[] getTokenHash() {
    return tokenHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  void replace(EnrollmentToken token, Instant createdAt) {
    this.tokenHash = token.hash();
    this.expiresAt = token.expiresAt();
    this.createdAt = createdAt;
  }

  EnrollmentToken toToken() {
    return new EnrollmentToken(vmId, tokenHash, expiresAt);
  }
}

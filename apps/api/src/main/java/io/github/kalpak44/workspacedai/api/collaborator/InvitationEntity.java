package io.github.kalpak44.workspacedai.api.collaborator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * An invitation into a workspace that has not been accepted yet. It holds neither the address nor
 * the code it was sent with, only their hashes.
 */
@Entity
@Table(name = "workspace_invitation")
class InvitationEntity {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "email_hash", nullable = false, updatable = false, length = 64)
  private String emailHash;

  @Column(name = "code_hash", nullable = false, length = 64)
  private String codeHash;

  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected InvitationEntity() {
    // For JPA.
  }

  InvitationEntity(UUID workspaceId, String emailHash) {
    this.id = UUID.randomUUID();
    this.workspaceId = workspaceId;
    this.emailHash = emailHash;
  }

  /** Sends it again: a new code, valid from now, and the old link stops working. */
  void reissue(String codeHash, UUID invitedBy, Instant now, Instant expiresAt) {
    this.codeHash = codeHash;
    this.invitedBy = invitedBy;
    // Postgres keeps microseconds, so the values read back later equal these.
    this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
  }

  UUID getId() {
    return id;
  }

  UUID getWorkspaceId() {
    return workspaceId;
  }

  String getEmailHash() {
    return emailHash;
  }

  UUID getInvitedBy() {
    return invitedBy;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }
}

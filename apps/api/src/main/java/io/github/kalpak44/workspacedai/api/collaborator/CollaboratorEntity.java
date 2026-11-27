package io.github.kalpak44.workspacedai.api.collaborator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A user who accepted an invitation into a workspace, and may do all but end or pay for it. */
@Entity
@Table(name = "workspace_collaborator")
@IdClass(CollaboratorEntity.Key.class)
class CollaboratorEntity {

  record Key(UUID workspaceId, UUID userId) implements Serializable {
    Key() {
      this(null, null);
    }
  }

  @Id
  @Column(name = "workspace_id")
  private UUID workspaceId;

  @Id
  @Column(name = "user_id")
  private UUID userId;

  // Null once the user who sent the invitation has gone.
  @Column(name = "invited_by", updatable = false)
  private UUID invitedBy;

  @Column(name = "joined_at", nullable = false, updatable = false)
  private Instant joinedAt;

  protected CollaboratorEntity() {
    // For JPA.
  }

  CollaboratorEntity(UUID workspaceId, UUID userId, UUID invitedBy) {
    this.workspaceId = workspaceId;
    this.userId = userId;
    this.invitedBy = invitedBy;
    // Postgres keeps microseconds, so the value read back later equals this one.
    this.joinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  UUID getUserId() {
    return userId;
  }

  UUID getInvitedBy() {
    return invitedBy;
  }

  Instant getJoinedAt() {
    return joinedAt;
  }
}

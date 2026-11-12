package io.github.kalpak44.workspacedai.api.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A backup as stored; its archive is in object storage under {@code objectKey}. */
@Entity
@Table(name = "workspace_backup")
public class BackupEntity {

  @Id private UUID id;

  @Column(name = "owner_id", nullable = false, updatable = false)
  private UUID ownerId;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(name = "workspace_name", nullable = false, length = 100, updatable = false)
  private String workspaceName;

  @Column(name = "object_key", nullable = false, length = 300, updatable = false)
  private String objectKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private BackupStatus status = BackupStatus.RUNNING;

  @Column(nullable = false, length = 20)
  private String phase = "preparing";

  @Column(nullable = false)
  private int progress;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(length = 500)
  private String error;

  @Column(nullable = false, updatable = false)
  private String manifest;

  // Set here rather than left to a column default, so the row just saved can report it.
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected BackupEntity() {
    // For JPA.
  }

  BackupEntity(UUID id, UUID ownerId, UUID workspaceId, String workspaceName, String manifest) {
    this.id = id;
    this.ownerId = ownerId;
    this.workspaceId = workspaceId;
    this.workspaceName = workspaceName;
    // Under the owner's prefix, so one user's archives can be listed or removed together.
    this.objectKey = "users/" + ownerId + "/workspaces/" + workspaceId + "/" + id + ".tar.gz";
    this.manifest = manifest;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public BackupStatus getStatus() {
    return status;
  }

  public String getPhase() {
    return phase;
  }

  public int getProgress() {
    return progress;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public String getError() {
    return error;
  }

  public String getManifest() {
    return manifest;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}

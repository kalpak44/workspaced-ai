package io.github.kalpak44.workspacedai.api.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A chat session as stored; its messages live in the harness, not the database. */
@Entity
@Table(name = "workspace_session")
public class WorkspaceSessionEntity {

  // Assigned before the insert, because the path is derived from it and the harness has to be
  // told the path before the row can be written.
  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(nullable = false, length = 500, updatable = false)
  private String path;

  @Column(name = "harness_session_id", nullable = false, length = 100, updatable = false)
  private String harnessSessionId;

  @Column(length = 200)
  private String title;

  @Column(nullable = false)
  private boolean available = true;

  public boolean isAvailable() {
    return available;
  }

  public void setAvailable(boolean available) {
    this.available = available;
  }

  // Set here rather than left to the column default, so the row just saved can report it.
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected WorkspaceSessionEntity() {
    // For JPA.
  }

  public WorkspaceSessionEntity(UUID id, UUID workspaceId, String path, String harnessSessionId) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.path = path;
    this.harnessSessionId = harnessSessionId;
    // Postgres keeps microseconds, so the value read back later equals the one returned now.
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getPath() {
    return path;
  }

  public String getHarnessSessionId() {
    return harnessSessionId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

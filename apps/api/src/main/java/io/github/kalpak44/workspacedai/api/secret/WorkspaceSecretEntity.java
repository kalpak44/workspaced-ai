package io.github.kalpak44.workspacedai.api.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A secret's name only; its value is in the workspace's runtime and nowhere else. */
@Entity
@Table(name = "workspace_secret")
public class WorkspaceSecretEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // A plain column, not a relation: the database cascade removes these with the workspace.
  @Column(name = "workspace_id", nullable = false, updatable = false)
  private UUID workspaceId;

  @Column(nullable = false, length = 100, updatable = false)
  private String name;

  // Set by the column default; read only to order the list.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected WorkspaceSecretEntity() {
    // For JPA.
  }

  public WorkspaceSecretEntity(UUID workspaceId, String name) {
    this.workspaceId = workspaceId;
    this.name = name;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getName() {
    return name;
  }
}

package io.github.kalpak44.workspacedai.api.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A workspace as stored; its status lives in the runtime, not the database. */
@Entity
@Table(name = "workspace")
public class WorkspaceEntity {

  // Generated on persist rather than by the database, so the id is known before the
  // insert is flushed and the pod can be named after it.
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String name;

  // Set by the column default; read only to order the list.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected WorkspaceEntity() {
    // For JPA.
  }

  public WorkspaceEntity(String name) {
    this.name = name;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

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

  // An id from ModelProvider; stored as text so a provider can be added without a migration.
  @Column(name = "model_provider", nullable = false, length = 50)
  private String modelProvider;

  // A model id at that provider; null keeps the harness's own default.
  @Column(length = 200)
  private String model;

  @Column(name = "session_ttl_enabled", nullable = false)
  private boolean sessionTtlEnabled;

  @Column(name = "session_ttl_hours", nullable = false)
  private int sessionTtlHours = 168;

  public boolean isSessionTtlEnabled() {
    return sessionTtlEnabled;
  }

  public int getSessionTtlHours() {
    return sessionTtlHours;
  }

  public void setSessionTtl(boolean enabled, int hours) {
    this.sessionTtlEnabled = enabled;
    this.sessionTtlHours = hours;
  }

  // Set by the column default; read only to order the list.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected WorkspaceEntity() {
    // For JPA.
  }

  public WorkspaceEntity(String name, String modelProvider) {
    this.name = name;
    this.modelProvider = modelProvider;
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

  public String getModelProvider() {
    return modelProvider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

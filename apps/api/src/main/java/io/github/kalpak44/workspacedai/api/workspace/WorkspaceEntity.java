package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.compute.ComputeProvider;
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
  // insert is flushed and the VM can be labelled with it.
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // The user it belongs to; its sessions, secrets and settings are reachable only by them.
  @Column(name = "owner_id", nullable = false, updatable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 100)
  private String name;

  // An id from ModelProvider; stored as text so a provider can be added without a migration.
  @Column(name = "model_provider", nullable = false, length = 50)
  private String modelProvider;

  // An id from ComputeProvider, fixed at creation: a VM cannot move between clouds.
  @Column(name = "compute_provider", nullable = false, updatable = false, length = 50)
  private String computeProvider = ComputeProvider.DEFAULT.id();

  @Column(name = "ai_connector_id")
  private UUID aiConnectorId;

  @jakarta.persistence.Embedded private ProviderChange providerChange = new ProviderChange();

  public ProviderChange getProviderChange() {
    return providerChange;
  }

  public void activateProvider(UUID connector, String provider) {
    if (!modelProvider.equals(provider)) model = null;
    aiConnectorId = connector;
    modelProvider = provider;
  }

  public UUID getAiConnectorId() {
    return aiConnectorId;
  }

  public void setAiConnectorId(UUID id) {
    aiConnectorId = id;
  }

  // The VM size it was created as; null where nothing runs it.
  @Column(name = "server_type", length = 20)
  private String serverType;

  // A model id at that provider; null keeps the harness's own default.
  @Column(length = 200)
  private String model;

  // An IANA zone; the harness reads it as USER_TIMEZONE from its next start.
  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone = "UTC";

  @Column(name = "session_ttl_enabled", nullable = false)
  private boolean sessionTtlEnabled;

  @Column(name = "session_ttl_hours", nullable = false)
  private int sessionTtlHours = 168;

  // Secrets changed since the runtime started; it sees them only once it restarts.
  @Column(name = "secrets_pending_restart", nullable = false)
  private boolean secretsPendingRestart;

  @Column(name = "configuration_revision", nullable = false)
  private long configurationRevision;

  public long getConfigurationRevision() {
    return configurationRevision;
  }

  // Whether its own agent may open and close ports with its `firewall` tool; on until the user
  // turns it off.
  @Column(name = "firewall_agent_allowed", nullable = false)
  private boolean firewallAgentAllowed = true;

  // Waiting for its provider key: it has no VM until it is launched.
  @Column(nullable = false)
  private boolean draft;

  // An account's free demo until its owner keeps it: never charged, and deleted at expiresAt.
  @Column(nullable = false)
  private boolean demo;

  // Set when a demo's VM starts; null for every other workspace.
  @Column(name = "expires_at")
  private Instant expiresAt;

  public boolean isDemo() {
    return demo;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  /** Turns the demo into a workspace its owner pays for, on the same VM. */
  void keep() {
    demo = false;
    expiresAt = null;
  }

  public boolean isDraft() {
    return draft;
  }

  public boolean isFirewallAgentAllowed() {
    return firewallAgentAllowed;
  }

  public boolean isSecretsPendingRestart() {
    return secretsPendingRestart;
  }

  public void setSecretsPendingRestart(boolean pending) {
    this.secretsPendingRestart = pending;
    if (pending) configurationRevision++;
  }

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

  public WorkspaceEntity(UUID ownerId, String name, String modelProvider, String timeZone) {
    this(ownerId, name, modelProvider, timeZone, false);
  }

  public WorkspaceEntity(
      UUID ownerId, String name, String modelProvider, String timeZone, boolean demo) {
    this.ownerId = ownerId;
    this.name = name;
    this.modelProvider = modelProvider;
    this.timeZone = timeZone;
    this.demo = demo;
  }

  /** One without a VM yet, which holds the size it will be launched as. */
  public static WorkspaceEntity draft(
      UUID ownerId,
      String name,
      String modelProvider,
      String serverType,
      String timeZone,
      boolean demo) {
    var entity = new WorkspaceEntity(ownerId, name, modelProvider, timeZone, demo);
    entity.serverType = serverType;
    entity.draft = true;
    return entity;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public void setTimeZone(String timeZone) {
    this.timeZone = timeZone;
  }

  public String getServerType() {
    return serverType;
  }

  public void setServerType(String serverType) {
    this.serverType = serverType;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
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

  public ComputeProvider getComputeProvider() {
    return ComputeProvider.byId(computeProvider).orElseThrow();
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

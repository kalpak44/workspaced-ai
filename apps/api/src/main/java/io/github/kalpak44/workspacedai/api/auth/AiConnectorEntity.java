package io.github.kalpak44.workspacedai.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_connector")
public class AiConnectorEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false)
  private String provider;

  @Column(name = "auth_type", nullable = false)
  private String authType = "api_key";

  @Column(name = "workspace_id")
  private UUID workspaceId;

  public String getAuthType() {
    return authType;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public boolean isChatGpt() {
    return "chatgpt".equals(authType);
  }

  static AiConnectorEntity chatGpt(UUID owner, UUID workspace, String name, String sealed) {
    var row = new AiConnectorEntity(owner, name, "openai", sealed);
    row.authType = "chatgpt";
    row.workspaceId = workspace;
    return row;
  }

  @Column(name = "encrypted_key", nullable = false, columnDefinition = "text")
  private String encryptedKey;

  @Column(name = "provider_notice_accepted_at")
  private Instant providerNoticeAcceptedAt;

  @Column(name = "provider_notice_version", length = 100)
  private String providerNoticeVersion;

  @Column(nullable = false, updatable = false)
  private boolean trial;

  @Column(name = "provider_key_hash", length = 200, updatable = false)
  private String providerKeyHash;

  @Column(name = "status_checked_at")
  private Instant statusCheckedAt;

  @Column(name = "observed_expires_at")
  private Instant observedExpiresAt;

  @Column(name = "budget_notice_cycle", length = 200)
  private String budgetNoticeCycle;

  @Column(name = "budget_notice_level", nullable = false)
  private int budgetNoticeLevel;

  public UUID getOwnerId() {
    return ownerId;
  }

  public Instant getObservedExpiresAt() {
    return observedExpiresAt;
  }

  public void setObservedExpiresAt(Instant at) {
    observedExpiresAt = at;
  }

  public void checkedAt(Instant at) {
    statusCheckedAt = at;
  }

  public String getBudgetNoticeCycle() {
    return budgetNoticeCycle;
  }

  public int getBudgetNoticeLevel() {
    return budgetNoticeLevel;
  }

  public void budgetNotice(String cycle, int level) {
    budgetNoticeCycle = cycle;
    budgetNoticeLevel = level;
  }

  public void acknowledgeProviderNotice() {
    if (providerNoticeAcceptedAt == null) {
      providerNoticeAcceptedAt = Instant.now();
      providerNoticeVersion = "ai-provider-notice-v1";
    }
  }

  public boolean isTrial() {
    return trial;
  }

  static AiConnectorEntity trial(UUID ownerId, String encryptedKey, String hash) {
    var row = new AiConnectorEntity(ownerId, "Trial", "openrouter", encryptedKey);
    row.trial = true;
    row.providerKeyHash = hash;
    // Provisioning a key does not assert that the user has read the provider notice.
    row.providerNoticeAcceptedAt = null;
    row.providerNoticeVersion = null;
    return row;
  }

  protected AiConnectorEntity() {}

  AiConnectorEntity(UUID ownerId, String name, String provider, String encryptedKey) {
    this.providerNoticeAcceptedAt = Instant.now();
    this.providerNoticeVersion = "ai-provider-notice-v1";
    this.ownerId = ownerId;
    this.name = name;
    this.provider = provider;
    this.encryptedKey = encryptedKey;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getProvider() {
    return provider;
  }

  String encryptedKey() {
    return encryptedKey;
  }
}

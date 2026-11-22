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

  @Column(name = "encrypted_key", nullable = false, columnDefinition = "text")
  private String encryptedKey;

  @Column(name = "provider_notice_accepted_at")
  private Instant providerNoticeAcceptedAt;

  @Column(name = "provider_notice_version", length = 100)
  private String providerNoticeVersion;

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

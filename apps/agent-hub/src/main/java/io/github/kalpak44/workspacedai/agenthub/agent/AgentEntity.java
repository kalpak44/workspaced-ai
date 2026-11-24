package io.github.kalpak44.workspacedai.agenthub.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of the API's {@code agent} table: the key a VM enrolled with. */
@Entity
@Table(name = "agent")
public class AgentEntity {

  @Id
  @Column(name = "vm_id")
  private UUID vmId;

  @Column(name = "public_key", nullable = false, updatable = false)
  private byte[] publicKey;

  @Column(name = "enrolled_at", nullable = false, updatable = false)
  private Instant enrolledAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;

  protected AgentEntity() {
    // For JPA.
  }

  AgentEntity(UUID vmId, byte[] publicKey, Instant enrolledAt) {
    this.vmId = vmId;
    this.publicKey = publicKey;
    this.enrolledAt = enrolledAt;
  }

  public byte[] getPublicKey() {
    return publicKey;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  void seen(Instant seenAt) {
    this.lastSeenAt = seenAt;
  }
}

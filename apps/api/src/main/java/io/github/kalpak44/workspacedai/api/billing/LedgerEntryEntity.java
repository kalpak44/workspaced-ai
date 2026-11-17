package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One change to a balance, never updated or deleted: the balance is always their sum. */
@Entity
@Table(name = "billing_ledger_entry")
class LedgerEntryEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "amount_micros", nullable = false)
  private long amountMicros;

  @Column(nullable = false, length = 20)
  private String kind;

  // Unique per kind, so a retried credit or charge is recorded once.
  @Column(nullable = false)
  private String reference;

  // Set by the database as the entry is inserted.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  protected LedgerEntryEntity() {}

  LedgerEntryEntity(UUID userId, long amountMicros, String kind, String reference) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.amountMicros = amountMicros;
    this.kind = kind;
    this.reference = reference;
  }

  long getAmountMicros() {
    return amountMicros;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}

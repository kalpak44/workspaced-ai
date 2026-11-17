package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment started through Stripe Checkout, or charged to the saved card by an automatic top-up.
 * Stripe issues the invoice once it is paid and keeps it; only its id and number are kept here.
 */
@Entity
@Table(name = "billing_top_up")
class TopUpEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TopUpStatus status;

  @Column(name = "checkout_session_id")
  private String checkoutSessionId;

  @Column(name = "stripe_invoice_id")
  private String stripeInvoiceId;

  @Column(name = "invoice_number", length = 100)
  private String invoiceNumber;

  // Set by the database, so it orders top-ups by the time they were stored.
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(nullable = false)
  private boolean automatic;

  protected TopUpEntity() {}

  TopUpEntity(UUID id, UUID userId, long amountCents) {
    this.id = id;
    this.userId = userId;
    this.amountCents = amountCents;
    this.status = TopUpStatus.PENDING;
  }

  /** One charged to the saved card when the balance ran low, rather than paid at a checkout. */
  static TopUpEntity automatic(UUID id, UUID userId, long amountCents) {
    var topUp = new TopUpEntity(id, userId, amountCents);
    topUp.automatic = true;
    return topUp;
  }

  boolean isAutomatic() {
    return automatic;
  }

  UUID getId() {
    return id;
  }

  UUID getUserId() {
    return userId;
  }

  long getAmountCents() {
    return amountCents;
  }

  TopUpStatus getStatus() {
    return status;
  }

  void setStatus(TopUpStatus status) {
    this.status = status;
  }

  String getCheckoutSessionId() {
    return checkoutSessionId;
  }

  void setCheckoutSessionId(String checkoutSessionId) {
    this.checkoutSessionId = checkoutSessionId;
  }

  String getStripeInvoiceId() {
    return stripeInvoiceId;
  }

  String getInvoiceNumber() {
    return invoiceNumber;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getPaidAt() {
    return paidAt;
  }

  /** Paid once: a later report fills in only what the first left out. */
  void markPaid(Instant at, String invoiceId, String invoiceNumber) {
    status = TopUpStatus.PAID;
    paidAt = paidAt == null ? at : paidAt;
    stripeInvoiceId = stripeInvoiceId == null ? invoiceId : stripeInvoiceId;
    this.invoiceNumber = this.invoiceNumber == null ? invoiceNumber : this.invoiceNumber;
  }
}

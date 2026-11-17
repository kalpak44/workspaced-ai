package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What one user has to spend, the Stripe customer their payments are filed under, and the card
 * charged when the balance runs low, if they saved one and turned that on.
 */
@Entity
@Table(name = "billing_account")
class BillingAccountEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  // Micro-units of the billing currency, so a charge for a fraction of a cent stays exact.
  @Column(name = "balance_micros", nullable = false)
  private long balanceMicros;

  @Column(name = "stripe_customer_id")
  private String stripeCustomerId;

  @Column(name = "payment_method_id")
  private String paymentMethodId;

  @Column(name = "card_label", length = 100)
  private String cardLabel;

  @Column(name = "auto_top_up_enabled", nullable = false)
  private boolean autoTopUpEnabled;

  @Column(name = "auto_top_up_below_cents")
  private Long autoTopUpBelowCents;

  @Column(name = "auto_top_up_amount_cents")
  private Long autoTopUpAmountCents;

  protected BillingAccountEntity() {}

  BillingAccountEntity(UUID userId) {
    this.userId = userId;
  }

  long getBalanceMicros() {
    return balanceMicros;
  }

  void add(long micros) {
    balanceMicros += micros;
  }

  String getStripeCustomerId() {
    return stripeCustomerId;
  }

  void setStripeCustomerId(String stripeCustomerId) {
    this.stripeCustomerId = stripeCustomerId;
  }

  UUID getUserId() {
    return userId;
  }

  /** Null while no card is saved. */
  String getPaymentMethodId() {
    return paymentMethodId;
  }

  /** How the card is shown, e.g. Visa •••• 4242; null while none is saved. */
  String getCardLabel() {
    return cardLabel;
  }

  void saveCard(String paymentMethodId, String label) {
    this.paymentMethodId = paymentMethodId;
    this.cardLabel = label;
  }

  /** Forgets the card, which leaves nothing for an automatic top-up to charge. */
  void forgetCard() {
    this.paymentMethodId = null;
    this.cardLabel = null;
    this.autoTopUpEnabled = false;
  }

  boolean isAutoTopUpEnabled() {
    return autoTopUpEnabled;
  }

  /** Null until the user first sets it. */
  Long getAutoTopUpBelowCents() {
    return autoTopUpBelowCents;
  }

  /** Null until the user first sets it. */
  Long getAutoTopUpAmountCents() {
    return autoTopUpAmountCents;
  }

  void setAutoTopUp(boolean enabled, long belowCents, long amountCents) {
    this.autoTopUpEnabled = enabled;
    this.autoTopUpBelowCents = belowCents;
    this.autoTopUpAmountCents = amountCents;
  }

  void disableAutoTopUp() {
    this.autoTopUpEnabled = false;
  }
}

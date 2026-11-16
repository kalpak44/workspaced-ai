package io.github.kalpak44.workspacedai.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** What one user has to spend, and the Stripe customer their payments are filed under. */
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
}

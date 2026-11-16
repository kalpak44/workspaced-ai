package io.github.kalpak44.workspacedai.api.billing;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** The calls billing makes to Stripe; {@link HttpStripe} makes them over its REST API. */
public interface Stripe {

  /** A new customer for the account, tagged with its user id. */
  String createCustomer(UUID userId, String name);

  /**
   * A Checkout Session that charges the amount once, plus VAT at the configured rate, and has
   * Stripe issue an invoice for both. The top-up id is its idempotency key, so a retried call
   * cannot open a second session.
   */
  CheckoutSession createCheckout(
      String customerId, UUID topUpId, long amountCents, URI successUrl, URI cancelUrl);

  /** The session as Stripe has it now, with its invoice once one is issued. */
  CheckoutSession checkout(String sessionId);

  /** Where the invoice's PDF can be downloaded right now; Stripe's links do not last for ever. */
  Optional<URI> invoicePdf(String invoiceId);

  /**
   * @param status open, complete or expired
   * @param paymentStatus paid, unpaid or no_payment_required
   * @param amountSubtotal what was bought, before tax
   * @param amountTotal what was charged, tax included
   * @param url the hosted payment page, only while the session is open
   */
  record CheckoutSession(
      String id,
      String status,
      String paymentStatus,
      long amountSubtotal,
      long amountTotal,
      String currency,
      URI url,
      String invoiceId,
      String invoiceNumber) {

    boolean paid() {
      return "complete".equals(status) && "paid".equals(paymentStatus);
    }

    boolean expired() {
      return "expired".equals(status);
    }
  }
}

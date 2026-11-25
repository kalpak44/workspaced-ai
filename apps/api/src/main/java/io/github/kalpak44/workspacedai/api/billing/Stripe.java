package io.github.kalpak44.workspacedai.api.billing;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** The calls billing makes to Stripe; {@link HttpStripe} makes them over its REST API. */
public interface Stripe {

  /**
   * A new customer for the account, tagged with its user id and nothing else: Checkout asks for the
   * email and billing details it needs, so the app hands Stripe no personal data of its own.
   */
  String createCustomer(UUID userId);

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
   * A Checkout Session in setup mode, which saves a card to charge later without taking a payment.
   *
   * @param successUrl may hold {CHECKOUT_SESSION_ID}, which Stripe fills in
   */
  CheckoutSession createCardSetup(String customerId, String successUrl, URI cancelUrl);

  /** The card a setup session saved; empty until the session is complete. */
  Optional<SavedCard> savedCard(String sessionId);

  /**
   * Charges the saved card without the user present, through an invoice for the amount plus VAT
   * that Stripe issues and keeps. The top-up id keys every call, so a retry charges once.
   *
   * @throws CardDeclinedException when the card is refused; the invoice is then voided
   */
  ChargedInvoice chargeCard(
      String customerId, String paymentMethodId, UUID topUpId, long amountCents);

  /**
   * The charge as Stripe has it now, with how much of it was refunded or disputed and the invoice
   * or Checkout Session it paid for; empty when this account has no such charge.
   */
  Optional<Charge> charge(String chargeId);

  /** Removes the card from the customer; one already gone is not an error. */
  void detachCard(String paymentMethodId);

  /**
   * @param customerId the customer the session was opened for
   * @param label how to show it, e.g. Visa •••• 4242
   */
  record SavedCard(String customerId, String paymentMethodId, String label) {}

  /**
   * @param amount what was charged, tax included, in cents
   * @param amountRefunded what has been refunded of it so far
   * @param disputed whether the cardholder disputed it, which takes the whole amount back until the
   *     dispute is decided
   * @param invoiceId the invoice it paid; null for a Checkout payment Stripe did not link to one
   * @param checkoutSessionId the Checkout Session it paid; null when it paid an invoice directly
   */
  record Charge(
      String id,
      long amount,
      long amountRefunded,
      boolean disputed,
      String invoiceId,
      String checkoutSessionId) {

    /** What the platform no longer has of the amount. */
    long lost() {
      return disputed ? amount : Math.min(amountRefunded, amount);
    }
  }

  /**
   * @param status paid once the charge went through
   * @param subtotal what was bought, before tax
   * @param total what was charged, tax included
   */
  record ChargedInvoice(
      String id, String number, String status, long subtotal, long total, String currency) {

    boolean paid() {
      return "paid".equals(status);
    }
  }

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

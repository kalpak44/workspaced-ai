package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingStore.TopUpRow;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds funds through Stripe Checkout. A top-up is credited only once Stripe reports its session
 * paid, whether the webhook or the returning browser asks first; neither is trusted on its own
 * word, since both make the API read the session back from Stripe.
 *
 * <p>No method here is transactional: each Stripe call is made between, never inside, the store's
 * transactions.
 */
@Service
public class BillingService {

  static final long MIN_TOP_UP_CENTS = 500;
  static final long MAX_TOP_UP_CENTS = 100_000;

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  private final BillingStore store;
  private final Stripe stripe;
  private final UserRepository users;
  private final BillingProperties properties;
  private final JsonMapper json;

  public BillingService(
      BillingStore store,
      Stripe stripe,
      UserRepository users,
      BillingProperties properties,
      JsonMapper json) {
    this.store = store;
    this.stripe = stripe;
    this.users = users;
    this.properties = properties;
    this.json = json;
  }

  public Billing billing(UUID user) {
    return new Billing(
        Math.floorDiv(store.balanceMicros(user), BillingStore.MICROS_PER_CENT),
        properties.currency(),
        store.topUps(user).stream().map(BillingService::view).toList());
  }

  public Checkout startTopUp(UUID user, long amountCents) {
    if (amountCents < MIN_TOP_UP_CENTS || amountCents > MAX_TOP_UP_CENTS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount out of range");
    }
    var customer =
        store
            .customerId(user)
            .orElseGet(() -> store.claimCustomerId(user, stripe.createCustomer(user, name(user))));
    var id = UUID.randomUUID();
    store.createTopUp(id, user, amountCents);
    Stripe.CheckoutSession session;
    try {
      session =
          stripe.createCheckout(
              customer, id, amountCents, returnUrl(id, "success"), returnUrl(id, "cancelled"));
    } catch (RuntimeException e) {
      // The user never saw a payment page, so there is nothing pending to show.
      store.deleteTopUp(id);
      throw e;
    }
    store.attachSession(id, session.id());
    return new Checkout(id, session.url());
  }

  /** Asks Stripe where a top-up stands, for the browser coming back from checkout. */
  public TopUp refresh(UUID user, UUID topUpId) {
    var row = owned(user, topUpId);
    if (row.checkoutSessionId() != null
        && (row.status() == TopUpStatus.PENDING
            || (row.status() == TopUpStatus.PAID && row.stripeInvoiceId() == null))) {
      settle(row.checkoutSessionId(), false);
      row = owned(user, topUpId);
    }
    return view(row);
  }

  /** Where the paid top-up's invoice PDF can be downloaded from Stripe right now. */
  public URI invoice(UUID user, UUID topUpId) {
    var row = owned(user, topUpId);
    if (row.status() != TopUpStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the top-up is not paid");
    }
    if (row.stripeInvoiceId() == null && row.checkoutSessionId() != null) {
      settle(row.checkoutSessionId(), false);
      row = owned(user, topUpId);
    }
    if (row.stripeInvoiceId() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Stripe has not issued the invoice yet");
    }
    return stripe
        .invoicePdf(row.stripeInvoiceId())
        .orElseThrow(
            () ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe has no invoice PDF yet"));
  }

  /**
   * Handles an event Stripe sent the webhook. Only checkout events matter; the session they name is
   * read back from Stripe rather than taken from the payload.
   *
   * @return false when the signature does not check out
   */
  public boolean handleEvent(byte[] payload, String signature) {
    var secret = properties.stripe().webhookSecret();
    if (secret == null || secret.isBlank()) {
      throw new PaymentsUnavailableException("no webhook signing secret is configured", null);
    }
    if (!StripeSignature.valid(signature, payload, secret, Instant.now())) {
      return false;
    }
    String type;
    String sessionId;
    try {
      var event = json.readTree(payload);
      type = event.path("type").asString();
      sessionId = event.path("data").path("object").path("id").asString();
    } catch (JacksonException e) {
      return false;
    }
    switch (type) {
      case "checkout.session.completed",
          "checkout.session.async_payment_succeeded",
          "checkout.session.expired" ->
          settle(sessionId, false);
      case "checkout.session.async_payment_failed" -> settle(sessionId, true);
      default -> log.debug("ignoring Stripe event {}", type);
    }
    return true;
  }

  private void settle(String sessionId, boolean failed) {
    var session = stripe.checkout(sessionId);
    var row = store.topUpBySession(sessionId).orElse(null);
    if (row == null) {
      log.warn("Stripe checkout session {} belongs to no top-up", sessionId);
      return;
    }
    if (session.paid()) {
      if (session.amountTotal() != row.amountCents()
          || !properties.currency().equalsIgnoreCase(session.currency())) {
        log.error(
            "top-up {} was for {} cents but Stripe charged {} {}; not credited",
            row.id(),
            row.amountCents(),
            session.amountTotal(),
            session.currency());
        return;
      }
      if (store.markPaid(row.id(), session.invoiceId(), session.invoiceNumber())) {
        log.info("credited top-up {} of {} cents", row.id(), row.amountCents());
      }
    } else if (session.expired()) {
      store.markUnpaid(row.id(), TopUpStatus.EXPIRED);
    } else if (failed) {
      store.markUnpaid(row.id(), TopUpStatus.FAILED);
    }
  }

  private TopUpRow owned(UUID user, UUID topUpId) {
    return store
        .topUp(topUpId, user)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such top-up"));
  }

  private String name(UUID user) {
    return users
        .findById(user)
        .map(
            u -> {
              var full =
                  Stream.of(u.getFirstName(), u.getLastName())
                      .filter(part -> part != null && !part.isBlank())
                      .collect(Collectors.joining(" "));
              return full.isEmpty() ? u.getUsername() : full;
            })
        .orElseThrow(() -> new IllegalStateException("no user " + user));
  }

  private URI returnUrl(UUID topUpId, String outcome) {
    return UriComponentsBuilder.fromUri(properties.returnUrl())
        .queryParam("top_up", topUpId)
        .queryParam("outcome", outcome)
        .build()
        .toUri();
  }

  private static TopUp view(TopUpRow row) {
    return new TopUp(
        row.id(),
        row.amountCents(),
        row.status(),
        row.createdAt(),
        row.paidAt(),
        row.invoiceNumber(),
        row.status() == TopUpStatus.PAID);
  }
}

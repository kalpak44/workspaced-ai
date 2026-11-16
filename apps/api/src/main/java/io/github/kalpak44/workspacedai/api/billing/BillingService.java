package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds funds through Stripe Checkout. A top-up is credited only once Stripe reports its session
 * paid, whether the webhook or the returning browser asks first; neither is trusted on its own
 * word, since both make the API read the session back from Stripe.
 *
 * <p>No method here is transactional as a whole: each Stripe call is made between, never inside,
 * the short transactions that change what is stored.
 */
@Service
public class BillingService {

  static final long MIN_TOP_UP_CENTS = 500;
  static final long MAX_TOP_UP_CENTS = 100_000;

  // A month is 730 hours on average, which is how an hourly charge adds up to the monthly price.
  static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(730);
  private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  private final BillingAccountRepository accounts;
  private final TopUpRepository topUps;
  private final UsageRepository usage;
  private final Ledger ledger;
  private final TransactionTemplate transactions;
  private final Stripe stripe;
  private final UserRepository users;
  private final BillingProperties properties;
  private final JsonMapper json;

  BillingService(
      BillingAccountRepository accounts,
      TopUpRepository topUps,
      UsageRepository usage,
      Ledger ledger,
      TransactionTemplate transactions,
      Stripe stripe,
      UserRepository users,
      BillingProperties properties,
      JsonMapper json) {
    this.accounts = accounts;
    this.topUps = topUps;
    this.usage = usage;
    this.ledger = ledger;
    this.transactions = transactions;
    this.stripe = stripe;
    this.users = users;
    this.properties = properties;
    this.json = json;
  }

  public Billing billing(UUID user) {
    return new Billing(
        Math.floorDiv(balanceMicros(user), Ledger.MICROS_PER_CENT),
        properties.currency(),
        topUps.findAllByUserIdAndStatusNotOrderByCreatedAtDesc(user, TopUpStatus.EXPIRED).stream()
            .map(BillingService::view)
            .toList());
  }

  /**
   * Refuses unless the balance covers a month of the price, so a new workspace is not deleted for
   * want of funds soon after it is made.
   *
   * @param monthlyPrice what the user pays a month, in the billing currency
   * @throws InsufficientFundsException when it does not
   */
  public void requireMonthOf(UUID user, BigDecimal monthlyPrice) {
    var balance = balanceMicros(user);
    if (balance < micros(monthlyPrice)) {
      throw new InsufficientFundsException(
          "This size costs "
              + amount(micros(monthlyPrice))
              + " a month and the balance is "
              + amount(balance)
              + "; add funds to create it.");
    }
  }

  /** Charges the workspace from now on, by the hour, at the monthly price spread over a month. */
  public void startCharging(UUID workspace, UUID user, BigDecimal monthlyPrice) {
    var hourly =
        monthlyPrice
            .multiply(MICROS)
            .divide(HOURS_PER_MONTH, 0, RoundingMode.HALF_UP)
            .longValueExact();
    usage.save(new UsageEntity(workspace, user, Math.max(hourly, 1), Instant.now()));
  }

  public Checkout startTopUp(UUID user, long amountCents) {
    if (amountCents < MIN_TOP_UP_CENTS || amountCents > MAX_TOP_UP_CENTS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount out of range");
    }
    var customer =
        accounts
            .findById(user)
            .map(BillingAccountEntity::getStripeCustomerId)
            .orElseGet(() -> claimCustomer(user, stripe.createCustomer(user, name(user))));
    var id = UUID.randomUUID();
    topUps.save(new TopUpEntity(id, user, amountCents));
    Stripe.CheckoutSession session;
    try {
      session =
          stripe.createCheckout(
              customer, id, amountCents, returnUrl(id, "success"), returnUrl(id, "cancelled"));
    } catch (RuntimeException e) {
      // The user never saw a payment page, so there is nothing pending to show.
      transactions.executeWithoutResult(_ -> topUps.deleteByIdAndStatus(id, TopUpStatus.PENDING));
      throw e;
    }
    transactions.executeWithoutResult(
        _ -> topUps.findById(id).ifPresent(topUp -> topUp.setCheckoutSessionId(session.id())));
    return new Checkout(id, session.url());
  }

  /** Asks Stripe where a top-up stands, for the browser coming back from checkout. */
  public TopUp refresh(UUID user, UUID topUpId) {
    var topUp = owned(user, topUpId);
    if (topUp.getCheckoutSessionId() != null
        && (topUp.getStatus() == TopUpStatus.PENDING
            || (topUp.getStatus() == TopUpStatus.PAID && topUp.getStripeInvoiceId() == null))) {
      settle(topUp.getCheckoutSessionId(), false);
      topUp = owned(user, topUpId);
    }
    return view(topUp);
  }

  /** Where the paid top-up's invoice PDF can be downloaded from Stripe right now. */
  public URI invoice(UUID user, UUID topUpId) {
    var topUp = owned(user, topUpId);
    if (topUp.getStatus() != TopUpStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the top-up is not paid");
    }
    if (topUp.getStripeInvoiceId() == null && topUp.getCheckoutSessionId() != null) {
      settle(topUp.getCheckoutSessionId(), false);
      topUp = owned(user, topUpId);
    }
    if (topUp.getStripeInvoiceId() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Stripe has not issued the invoice yet");
    }
    return stripe
        .invoicePdf(topUp.getStripeInvoiceId())
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
    var topUp = topUps.findByCheckoutSessionId(sessionId).orElse(null);
    if (topUp == null) {
      log.warn("Stripe checkout session {} belongs to no top-up", sessionId);
      return;
    }
    if (session.paid()) {
      if (session.amountTotal() != topUp.getAmountCents()
          || !properties.currency().equalsIgnoreCase(session.currency())) {
        log.error(
            "top-up {} was for {} cents but Stripe charged {} {}; not credited",
            topUp.getId(),
            topUp.getAmountCents(),
            session.amountTotal(),
            session.currency());
        return;
      }
      if (credit(topUp.getId(), session.invoiceId(), session.invoiceNumber())) {
        log.info("credited top-up {} of {} cents", topUp.getId(), topUp.getAmountCents());
      }
    } else if (session.expired()) {
      markUnpaid(topUp.getId(), TopUpStatus.EXPIRED);
    } else if (failed) {
      markUnpaid(topUp.getId(), TopUpStatus.FAILED);
    }
  }

  /**
   * Marks the top-up paid and credits it once. The top-up stays locked meanwhile, so the webhook
   * and the returning browser reporting it at once credit it once.
   */
  boolean credit(UUID topUpId, String invoiceId, String invoiceNumber) {
    return Boolean.TRUE.equals(
        transactions.execute(
            _ -> {
              var topUp =
                  topUps
                      .findLockedById(topUpId)
                      .orElseThrow(() -> new IllegalStateException("no top-up " + topUpId));
              topUp.markPaid(Instant.now(), invoiceId, invoiceNumber);
              return ledger.post(
                  topUp.getUserId(),
                  topUp.getAmountCents() * Ledger.MICROS_PER_CENT,
                  Ledger.TOP_UP,
                  topUpId.toString());
            }));
  }

  // Only a pending top-up can end unpaid; a paid one stays paid whatever arrives late.
  private void markUnpaid(UUID topUpId, TopUpStatus status) {
    transactions.executeWithoutResult(
        _ ->
            topUps
                .findById(topUpId)
                .filter(topUp -> topUp.getStatus() == TopUpStatus.PENDING)
                .ifPresent(topUp -> topUp.setStatus(status)));
  }

  // Keeps the first customer stored, so two racing top-ups still file under one.
  private String claimCustomer(UUID user, String customerId) {
    return transactions.execute(
        _ -> {
          var account = ledger.account(user);
          if (account.getStripeCustomerId() == null) {
            account.setStripeCustomerId(customerId);
          }
          return account.getStripeCustomerId();
        });
  }

  private long balanceMicros(UUID user) {
    return accounts.findById(user).map(BillingAccountEntity::getBalanceMicros).orElse(0L);
  }

  private TopUpEntity owned(UUID user, UUID topUpId) {
    return topUps
        .findByIdAndUserId(topUpId, user)
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

  private static long micros(BigDecimal amount) {
    return amount.multiply(MICROS).setScale(0, RoundingMode.CEILING).longValueExact();
  }

  private String amount(long micros) {
    return BigDecimal.valueOf(micros, 6).setScale(2, RoundingMode.FLOOR).toPlainString()
        + " "
        + properties.currency().toUpperCase(Locale.ROOT);
  }

  private URI returnUrl(UUID topUpId, String outcome) {
    return UriComponentsBuilder.fromUri(properties.returnUrl())
        .queryParam("top_up", topUpId)
        .queryParam("outcome", outcome)
        .build()
        .toUri();
  }

  private static TopUp view(TopUpEntity topUp) {
    return new TopUp(
        topUp.getId(),
        topUp.getAmountCents(),
        topUp.getStatus(),
        topUp.getCreatedAt(),
        topUp.getPaidAt(),
        topUp.getInvoiceNumber(),
        topUp.getStatus() == TopUpStatus.PAID);
  }
}

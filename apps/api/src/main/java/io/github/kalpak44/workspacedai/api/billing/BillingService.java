package io.github.kalpak44.workspacedai.api.billing;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.notification.NotificationMessage;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import io.github.kalpak44.workspacedai.api.notification.Notifications;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
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

  // What the auto top-up form starts from before the user sets it.
  private static final long DEFAULT_AUTO_BELOW_CENTS = 1000;
  private static final long DEFAULT_AUTO_AMOUNT_CENTS = 2000;
  private static final String CARD_SESSION = "card_session";

  // A month is 730 hours on average, which is how an hourly charge adds up to the monthly price.
  static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(730);
  static final int USAGE_DAYS = 30;
  private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000);

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  private final BillingAccountRepository accounts;
  private final TopUpRepository topUps;
  private final UsageRepository usage;
  private final LedgerEntryRepository entries;
  private final WorkspaceRepository workspaces;
  private final Ledger ledger;
  private final TransactionTemplate transactions;
  private final Stripe stripe;
  private final UserRepository users;
  private final BillingProperties properties;
  private final JsonMapper json;
  private final NotificationService notifications;
  private final List<TopUpRewards> rewards;

  BillingService(
      BillingAccountRepository accounts,
      TopUpRepository topUps,
      UsageRepository usage,
      LedgerEntryRepository entries,
      WorkspaceRepository workspaces,
      Ledger ledger,
      TransactionTemplate transactions,
      Stripe stripe,
      UserRepository users,
      BillingProperties properties,
      JsonMapper json,
      NotificationService notifications,
      List<TopUpRewards> rewards) {
    this.accounts = accounts;
    this.topUps = topUps;
    this.usage = usage;
    this.entries = entries;
    this.workspaces = workspaces;
    this.ledger = ledger;
    this.transactions = transactions;
    this.stripe = stripe;
    this.users = users;
    this.properties = properties;
    this.json = json;
    this.notifications = notifications;
    this.rewards = rewards;
  }

  public Billing billing(UUID user) {
    var now = Instant.now();
    var balance = balanceMicros(user);
    // An admin's workspaces are free, so nothing of theirs is shown as charged.
    var rows =
        (isFree(user) ? List.<UsageEntity>of() : usage.findAllByUserId(user))
            .stream().collect(Collectors.toMap(UsageEntity::getWorkspaceId, row -> row));
    // A paused workspace costs nothing until it starts again.
    var hourlyTotal =
        rows.values().stream()
            .filter(row -> row.getPausedAt() == null)
            .mapToLong(UsageEntity::getHourlyMicros)
            .sum();
    return new Billing(
        Math.floorDiv(balance, Ledger.MICROS_PER_CENT),
        properties.currency(),
        properties.vatPercent(),
        cents(reactivationFeeMicros()),
        properties.pausedFor().toHours(),
        properties.requireCovered().toHours(),
        cents(monthOf(hourlyTotal)),
        hourlyTotal == 0
            ? null
            : now.plus(hoursCovered(balance, hourlyTotal), ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS),
        workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user).stream()
            .filter(workspace -> rows.containsKey(workspace.getId()))
            .map(workspace -> spend(workspace.getId(), workspace.getName(), rows))
            .toList(),
        usageByDay(user, now),
        accounts.findById(user).map(BillingAccountEntity::getCardLabel).orElse(null),
        accounts.findById(user).map(BillingService::autoTopUp).orElse(autoTopUp(null)),
        topUps.findAllByUserIdAndStatusNotOrderByCreatedAtDesc(user, TopUpStatus.EXPIRED).stream()
            .map(BillingService::view)
            .toList());
  }

  /**
   * Refuses unless the balance covers {@code require-covered} of the price together with the
   * workspaces the user already has, which draw on the same balance, so no workspace is paused for
   * want of funds soon after a new one is made.
   *
   * @param monthlyPrice what the user pays a month, in the billing currency
   * @throws InsufficientFundsException when it does not
   */
  public void requireCovered(UUID user, BigDecimal monthlyPrice) {
    var hours = properties.requireCovered().toHours();
    var balance = balanceMicros(user);
    var price = hourlyOf(monthlyPrice) * hours;
    var running =
        usage.findAllByUserId(user).stream().mapToLong(UsageEntity::getHourlyMicros).sum() * hours;
    if (balance >= running + price) {
      return;
    }
    var span = days(hours);
    if (running == 0) {
      throw new InsufficientFundsException(
          "This size costs "
              + amount(price)
              + " for "
              + span
              + " and the balance is "
              + amount(balance)
              + "; add funds to create it.",
          price - balance);
    }
    throw new InsufficientFundsException(
        "Your workspaces cost "
            + amount(running)
            + " for "
            + span
            + " and this size adds "
            + amount(price)
            + ", but the balance is "
            + amount(balance)
            + "; add funds to cover "
            + span
            + " of them all.",
        running + price - balance);
  }

  /** "7 days", "36 hours": a coverage period as the user is told it. */
  private static String days(long hours) {
    if (hours % 24 != 0) {
      return hours + (hours == 1 ? " hour" : " hours");
    }
    var days = hours / 24;
    return days + (days == 1 ? " day" : " days");
  }

  /** Whole hours the balance pays for at the hourly charge; none when it is not positive. */
  static long hoursCovered(long balanceMicros, long hourlyMicros) {
    return Math.max(balanceMicros, 0) / hourlyMicros;
  }

  /** Charges the workspace from now on, by the hour, at the monthly price spread over a month. */
  public void startCharging(UUID workspace, UUID user, BigDecimal monthlyPrice) {
    usage.save(new UsageEntity(workspace, user, hourlyOf(monthlyPrice), Instant.now()));
  }

  /**
   * As {@link #startCharging}, for a workspace found running with nothing charging it. One whose
   * create started charging it meanwhile is left as it is.
   *
   * @return whether this started the charge
   */
  public boolean startChargingIfUncharged(UUID workspace, UUID user, BigDecimal monthlyPrice) {
    var hourly = hourlyOf(monthlyPrice);
    var inserted =
        transactions.execute(_ -> usage.insertIfAbsent(workspace, user, hourly, Instant.now()));
    return inserted != null && inserted == 1;
  }

  public boolean isCharged(UUID workspace) {
    return usage.existsById(workspace);
  }

  /** The monthly price spread over a month's hours, never free. */
  private static long hourlyOf(BigDecimal monthlyPrice) {
    var hourly =
        monthlyPrice
            .multiply(MICROS)
            .divide(HOURS_PER_MONTH, 0, RoundingMode.HALF_UP)
            .longValueExact();
    return Math.max(hourly, 1);
  }

  public Checkout startTopUp(UUID user, long amountCents) {
    if (amountCents < MIN_TOP_UP_CENTS || amountCents > MAX_TOP_UP_CENTS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount out of range");
    }
    var customer = customer(user);
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

  /**
   * Opens a Stripe page that saves a card for automatic top-ups without charging it. The browser
   * comes back with ?card_session=, which {@link #confirmCard} takes.
   */
  public CardCheckout startCardSetup(UUID user) {
    // Stripe fills the placeholder in; it must reach Stripe unencoded.
    var success =
        UriComponentsBuilder.fromUri(properties.returnUrl())
            .queryParam(CARD_SESSION, "SESSION_ID")
            .build()
            .toUriString()
            .replace("SESSION_ID", "{CHECKOUT_SESSION_ID}");
    var cancel =
        UriComponentsBuilder.fromUri(properties.returnUrl())
            .queryParam("card", "cancelled")
            .build()
            .toUri();
    return new CardCheckout(stripe.createCardSetup(customer(user), success, cancel).url());
  }

  /**
   * Keeps the card the browser's setup session saved, for the browser coming back before Stripe's
   * webhook does. A session of another customer's is not found.
   */
  public void confirmCard(UUID user, String sessionId) {
    var card =
        stripe
            .savedCard(sessionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "the card is not saved yet; try again shortly"));
    var account =
        accounts
            .findByStripeCustomerId(card.customerId())
            .filter(found -> found.getUserId().equals(user))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such card setup"));
    keepCard(account.getUserId(), card);
  }

  /** Forgets the saved card, which also turns automatic top-ups off. */
  public void removeCard(UUID user) {
    var removed =
        transactions.execute(
            _ -> {
              var account = ledger.account(user);
              var paymentMethod = account.getPaymentMethodId();
              account.forgetCard();
              return paymentMethod;
            });
    if (removed != null) {
      detachQuietly(removed);
    }
  }

  /**
   * Turns automatic top-ups on or off. They charge the saved card, so turning them on without one
   * is refused.
   */
  public AutoTopUp setAutoTopUp(UUID user, AutoTopUp settings) {
    return transactions.execute(
        _ -> {
          var account = ledger.account(user);
          if (settings.enabled() && account.getPaymentMethodId() == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "save a card before turning automatic top-ups on");
          }
          account.setAutoTopUp(settings.enabled(), settings.belowCents(), settings.amountCents());
          return autoTopUp(account);
        });
  }

  // The newest card replaces the one before, which is detached so Stripe keeps one alone.
  private void keepCard(UUID user, Stripe.SavedCard card) {
    var replaced =
        transactions.execute(
            _ -> {
              var account = ledger.account(user);
              var before = account.getPaymentMethodId();
              account.saveCard(card.paymentMethodId(), card.label());
              return card.paymentMethodId().equals(before) ? null : before;
            });
    if (replaced != null) {
      detachQuietly(replaced);
    }
  }

  // The card is already forgotten here; one left on the customer is only never charged.
  private void detachQuietly(String paymentMethodId) {
    try {
      stripe.detachCard(paymentMethodId);
    } catch (RuntimeException e) {
      log.warn("could not detach card {} at Stripe", paymentMethodId, e);
    }
  }

  private static AutoTopUp autoTopUp(BillingAccountEntity account) {
    if (account == null) {
      return new AutoTopUp(false, DEFAULT_AUTO_BELOW_CENTS, DEFAULT_AUTO_AMOUNT_CENTS);
    }
    return new AutoTopUp(
        account.isAutoTopUpEnabled(),
        Objects.requireNonNullElse(account.getAutoTopUpBelowCents(), DEFAULT_AUTO_BELOW_CENTS),
        Objects.requireNonNullElse(account.getAutoTopUpAmountCents(), DEFAULT_AUTO_AMOUNT_CENTS));
  }

  // The user's Stripe customer, created on first use.
  String customer(UUID user) {
    return accounts
        .findById(user)
        .map(BillingAccountEntity::getStripeCustomerId)
        .orElseGet(() -> claimCustomer(user, stripe.createCustomer(user)));
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
    String mode;
    String disputedCharge;
    try {
      var event = json.readTree(payload);
      type = event.path("type").asString();
      sessionId = event.path("data").path("object").path("id").asString();
      mode = event.path("data").path("object").path("mode").asString("payment");
      disputedCharge = event.path("data").path("object").path("charge").asString("");
    } catch (JacksonException e) {
      return false;
    }
    // A setup session saves a card, and only its completion matters.
    if ("setup".equals(mode)) {
      if ("checkout.session.completed".equals(type)) {
        stripe.savedCard(sessionId).ifPresent(this::keepCardOfCustomer);
      }
      return true;
    }
    switch (type) {
      case "checkout.session.completed",
          "checkout.session.async_payment_succeeded",
          "checkout.session.expired" ->
          settle(sessionId, false);
      case "checkout.session.async_payment_failed" -> settle(sessionId, true);
      case "invoice.paid" -> stripe.automaticInvoice(sessionId).ifPresent(this::settleAutomatic);
      // The object is the charge for a refund and the dispute for a dispute.
      case "charge.refunded" -> reverse(sessionId);
      case "charge.dispute.created" -> reverse(disputedCharge);
      default -> log.debug("ignoring Stripe event {}", type);
    }
    return true;
  }

  /** Recovery is independent of the current card or whether new charges are enabled. */
  boolean recoverAutomatic(TopUpEntity topUp) {
    var found =
        topUp.getStripeInvoiceId() == null
            ? accounts
                .findById(topUp.getUserId())
                .map(BillingAccountEntity::getStripeCustomerId)
                .flatMap(customer -> stripe.findAutomaticInvoice(customer, topUp.getId()))
            : stripe.automaticInvoice(topUp.getStripeInvoiceId());
    return found.map(this::settleAutomatic).orElse(false);
  }

  private boolean settleAutomatic(Stripe.AutomaticInvoice payment) {
    var found = topUps.findById(payment.topUpId());
    if (found.isEmpty() || !found.get().isAutomatic()) return false;
    var topUp = found.get();
    var customer =
        accounts.findById(topUp.getUserId()).map(BillingAccountEntity::getStripeCustomerId);
    if (customer.filter(payment.customerId()::equals).isEmpty()) return false;
    var invoice = payment.invoice();
    if (!invoice.paid()) return false;
    if (invoice.subtotal() != topUp.getAmountCents()
        || invoice.total() != topUp.getAmountCents() + vatCents(topUp.getAmountCents())
        || !properties.currency().equalsIgnoreCase(invoice.currency())) {
      throw new StripeFailedException("automatic invoice does not match the top-up");
    }
    credit(topUp.getId(), invoice.id(), invoice.number());
    return true;
  }

  private void keepCardOfCustomer(Stripe.SavedCard card) {
    accounts
        .findByStripeCustomerId(card.customerId())
        .ifPresentOrElse(
            account -> keepCard(account.getUserId(), card),
            () -> log.warn("Stripe customer {} belongs to no account", card.customerId()));
  }

  private void settle(String sessionId, boolean failed) {
    var session = stripe.checkout(sessionId);
    var topUp = topUps.findByCheckoutSessionId(sessionId).orElse(null);
    if (topUp == null) {
      log.warn("Stripe checkout session {} belongs to no top-up", sessionId);
      return;
    }
    if (session.paid()) {
      // VAT is the tax authority's, so the balance gets the amount before tax.
      var total = topUp.getAmountCents() + vatCents(topUp.getAmountCents());
      if (session.amountSubtotal() != topUp.getAmountCents()
          || session.amountTotal() != total
          || !properties.currency().equalsIgnoreCase(session.currency())) {
        log.error(
            "top-up {} was for {} cents, {} with VAT, but Stripe charged {} of {} {}; not credited",
            topUp.getId(),
            topUp.getAmountCents(),
            total,
            session.amountSubtotal(),
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
   * Marks the top-up paid and credits it once, thanking the user then. The top-up stays locked
   * meanwhile, so the webhook and the returning browser reporting it at once credit it once.
   */
  boolean credit(UUID topUpId, String invoiceId, String invoiceNumber) {
    return Boolean.TRUE.equals(
        transactions.execute(
            _ -> {
              var topUp =
                  topUps
                      .findLockedById(topUpId)
                      .orElseThrow(() -> new IllegalStateException("no top-up " + topUpId));
              if (topUp.isAutomatic()) topUp.rememberInvoice(invoiceId);
              topUp.markPaid(Instant.now(), invoiceId, invoiceNumber);
              var micros = topUp.getAmountCents() * Ledger.MICROS_PER_CENT;
              var credited =
                  ledger.post(topUp.getUserId(), micros, Ledger.TOP_UP, topUpId.toString());
              if (credited) {
                notifications.send(topUp.getUserId(), Notifications.topUpPaid(amount(micros)));
                reward(topUp.getUserId(), topUpId, micros);
              }
              return credited;
            }));
  }

  // Only once the top-up itself is credited, so a reward is never paid on credit and is posted
  // once with it, the top-up's id making each entry unique.
  private void reward(UUID user, UUID topUpId, long micros) {
    for (var program : rewards) {
      for (var reward : program.paid(user, topUpId, micros)) {
        if (reward.micros() > 0
            && ledger.post(reward.user(), reward.micros(), reward.kind(), topUpId.toString())) {
          notifications.send(reward.user(), reward.message().apply(amount(reward.micros())));
        }
      }
    }
  }

  /**
   * Takes back what a paid top-up credited, in proportion to how much of its charge was refunded or
   * disputed: its balance, and every reward posted with it, each from whoever received it. Each
   * refund of the same charge takes back only what the earlier ones did not, and a balance may go
   * negative. The charge is read back from Stripe rather than taken from the event.
   */
  private void reverse(String chargeId) {
    if (chargeId.isBlank()) {
      return;
    }
    var charge = stripe.charge(chargeId).orElse(null);
    if (charge == null) {
      log.warn("Stripe has no charge {}", chargeId);
      return;
    }
    var found =
        Optional.ofNullable(charge.invoiceId())
            .flatMap(topUps::findByStripeInvoiceId)
            .or(
                () ->
                    Optional.ofNullable(charge.checkoutSessionId())
                        .flatMap(topUps::findByCheckoutSessionId));
    if (found.isEmpty()) {
      log.warn("Stripe charge {} belongs to no top-up", chargeId);
      return;
    }
    if (charge.amount() <= 0 || charge.lost() <= 0) {
      return;
    }
    transactions.executeWithoutResult(
        _ -> {
          var topUp = topUps.findLockedById(found.get().getId()).orElseThrow();
          if (topUp.getStatus() != TopUpStatus.PAID) {
            return;
          }
          var reference = topUp.getId().toString();
          // The top-up first, so accounts are locked in the order crediting it locked them.
          var credited =
              entries.findAllByReference(reference).stream()
                  .sorted(Comparator.comparing(entry -> !Ledger.TOP_UP.equals(entry.getKind())))
                  .toList();
          for (var entry : credited) {
            var prefix = reference + ":" + entry.getKind() + ":";
            var due =
                BigInteger.valueOf(entry.getAmountMicros())
                    .multiply(BigInteger.valueOf(charge.lost()))
                    .divide(BigInteger.valueOf(charge.amount()))
                    .longValueExact();
            var taken = -entries.sumByKindAndReferencePrefix(Ledger.REVERSAL, prefix);
            var micros = due - taken;
            if (micros <= 0) {
              continue;
            }
            if (!Ledger.TOP_UP.equals(entry.getKind())) {
              rewards.forEach(
                  program -> program.reversed(topUp.getUserId(), entry.getKind(), micros));
            }
            if (ledger.post(entry.getUserId(), -micros, Ledger.REVERSAL, prefix + charge.lost())) {
              notifications.send(
                  entry.getUserId(),
                  Ledger.TOP_UP.equals(entry.getKind())
                      ? Notifications.topUpReversed(amount(micros))
                      : Notifications.rewardReversed(amount(micros)));
            }
          }
          log.info(
              "took back {} of {} cents of top-up {}", charge.lost(), charge.amount(), reference);
        });
  }

  // Only a pending top-up can end unpaid; a paid one stays paid whatever arrives late. The user is
  // warned once, when it stops being pending.
  private void markUnpaid(UUID topUpId, TopUpStatus status) {
    transactions.executeWithoutResult(
        _ ->
            topUps
                .findLockedById(topUpId)
                .filter(topUp -> topUp.getStatus() == TopUpStatus.PENDING)
                .ifPresent(
                    topUp -> {
                      topUp.setStatus(status);
                      notifications.send(topUp.getUserId(), unpaid(topUp, status));
                    }));
  }

  private NotificationMessage unpaid(TopUpEntity topUp, TopUpStatus status) {
    var amount = amount(topUp.getAmountCents() * Ledger.MICROS_PER_CENT);
    return status == TopUpStatus.FAILED
        ? Notifications.topUpFailed(amount)
        : Notifications.topUpExpired(amount);
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

  /** Rounded half up per line item, as Stripe rounds the tax it charges. */
  long vatCents(long amountCents) {
    return BigDecimal.valueOf(amountCents)
        .multiply(BigDecimal.valueOf(properties.vatPercent()))
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private WorkspaceSpend spend(UUID id, String name, Map<UUID, UsageEntity> rows) {
    var row = rows.get(id);
    return new WorkspaceSpend(
        id,
        name,
        cents(monthOf(row.getHourlyMicros())),
        row.getPausedAt() == null ? null : row.getPausedAt().plus(properties.pausedFor()));
  }

  // Charges are posted as each hour starts, so what was posted on a day is what that day cost.
  private List<DailySpend> usageByDay(UUID user, Instant now) {
    var since = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(USAGE_DAYS - 1L);
    var byDay = new TreeMap<LocalDate, Long>(Comparator.reverseOrder());
    entries
        .findAllByUserIdAndKindAndCreatedAtGreaterThanEqual(
            user, Ledger.USAGE, since.atStartOfDay(ZoneOffset.UTC).toInstant())
        .forEach(
            entry ->
                byDay.merge(
                    LocalDate.ofInstant(entry.getCreatedAt(), ZoneOffset.UTC),
                    -entry.getAmountMicros(),
                    Long::sum));
    return byDay.entrySet().stream()
        .map(day -> new DailySpend(day.getKey(), cents(day.getValue())))
        .toList();
  }

  private static long monthOf(long hourlyMicros) {
    return HOURS_PER_MONTH.multiply(BigDecimal.valueOf(hourlyMicros)).longValueExact();
  }

  private static long cents(long micros) {
    return BigDecimal.valueOf(micros)
        .divide(BigDecimal.valueOf(Ledger.MICROS_PER_CENT), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private long balanceMicros(UUID user) {
    return accounts.findById(user).map(BillingAccountEntity::getBalanceMicros).orElse(0L);
  }

  private TopUpEntity owned(UUID user, UUID topUpId) {
    return topUps
        .findByIdAndUserId(topUpId, user)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such top-up"));
  }

  private static long micros(BigDecimal amount) {
    return amount.multiply(MICROS).setScale(0, RoundingMode.CEILING).longValueExact();
  }

  /**
   * Whether the user's workspaces cost nothing, as an admin's do. Checked as each hour is charged,
   * so a user made an admin stops paying from then on.
   */
  boolean isFree(UUID user) {
    return users.findById(user).map(u -> u.getRole() == Role.ADMIN).orElse(false);
  }

  /** What starting a workspace paused for want of funds again takes from the balance. */
  long reactivationFeeMicros() {
    return micros(properties.reactivationFee());
  }

  /** "12.40 EUR": rounded down to the cent. */
  /** The amount as users are shown it, e.g. 12.50 EUR. */
  public String amount(long micros) {
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
        topUp.getStatus() == TopUpStatus.PAID,
        topUp.isAutomatic());
  }
}

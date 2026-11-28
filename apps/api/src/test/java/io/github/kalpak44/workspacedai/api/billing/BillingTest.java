package io.github.kalpak44.workspacedai.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationFixtures;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Against a server that answers as Stripe does, through the real client. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class BillingTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeStripe STRIPE = new FakeStripe();
  private static final String WEBHOOK_SECRET = "whsec_test";

  @LocalServerPort int port;
  @Autowired LedgerEntryRepository ledger;
  @Autowired TopUpRepository topUps;
  @Autowired BillingAccountRepository accounts;
  @Autowired UsageRepository usage;
  @Autowired BillingService billing;
  @Autowired UserRepository users;
  @Autowired NotificationService notifications;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transaction;
  @Autowired AutoTopUps autoTopUps;
  @Autowired Stripe stripe;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.billing.stripe.url", STRIPE::url);
    registry.add("workspaced.billing.stripe.secret-key", () -> FakeStripe.SECRET_KEY);
    registry.add("workspaced.billing.stripe.webhook-secret", () -> WEBHOOK_SECRET);
    registry.add("workspaced.billing.return-url", () -> "https://ai.workspaced.test/billing");
  }

  @AfterAll
  static void stop() {
    STRIPE.close();
  }

  @BeforeEach
  void clean() {
    ledger.deleteAll();
    topUps.deleteAll();
    usage.deleteAll();
    accounts.deleteAll();
    transaction.executeWithoutResult(_ -> NotificationFixtures.deleteAll(entityManager));
    STRIPE.reset();
  }

  @Test
  void startsWithAnEmptyBalance() throws Exception {
    var body = read(get("/billing"));
    assertThat(body.get("balanceCents").asLong()).isZero();
    assertThat(body.get("currency").asString()).isEqualTo("eur");
    assertThat(body.get("vatPercent").asInt()).isEqualTo(20);
    assertThat(body.get("topUps")).isEmpty();
  }

  @Test
  void opensACheckoutThatIssuesAnInvoiceAndShowsItPending() throws Exception {
    var response = startTopUp(2500);

    assertThat(response.statusCode()).isEqualTo(201);
    var checkout = read(response);
    var id = checkout.get("id").asString();
    assertThat(checkout.get("checkoutUrl").asString())
        .isEqualTo("https://checkout.stripe.test/" + STRIPE.lastSessionId());
    var sent = STRIPE.checkouts.getLast();
    assertThat(sent)
        .containsEntry("mode", "payment")
        .containsEntry("line_items[0][price_data][unit_amount]", "2500")
        .containsEntry("line_items[0][price_data][currency]", "eur")
        .containsEntry("invoice_creation[enabled]", "true")
        .containsKey("line_items[0][tax_rates][0]")
        .containsEntry("client_reference_id", id)
        .containsEntry(
            "success_url", "https://ai.workspaced.test/billing?top_up=" + id + "&outcome=success");
    assertThat(STRIPE.idempotencyKeys.getLast()).isEqualTo(id);
    // Stripe learns who the user is from Checkout, never from the app.
    assertThat(STRIPE.customers.getLast())
        .containsOnlyKeys("metadata[user_id]")
        .containsEntry("metadata[user_id]", TestUsers.ADMIN.toString());

    var topUp = read(get("/billing")).get("topUps").get(0);
    assertThat(topUp.get("status").asString()).isEqualTo("PENDING");
    assertThat(topUp.get("invoiceAvailable").asBoolean()).isFalse();
    assertThat(read(get("/billing")).get("balanceCents").asLong()).isZero();
  }

  @Test
  void filesEveryTopUpUnderOneCustomer() throws Exception {
    startTopUp(1000);
    startTopUp(1000);

    assertThat(STRIPE.checkouts.get(STRIPE.checkouts.size() - 2).get("customer"))
        .isEqualTo(STRIPE.checkouts.getLast().get("customer"));
  }

  @Test
  void creditsOncePaidHoweverOftenItIsRefreshed() throws Exception {
    var id = read(startTopUp(2500)).get("id").asString();
    assertThat(read(post("/billing/top-ups/" + id + "/refresh")).get("status").asString())
        .isEqualTo("PENDING");

    STRIPE.pay(STRIPE.lastSessionId());
    var paid = read(post("/billing/top-ups/" + id + "/refresh"));
    post("/billing/top-ups/" + id + "/refresh");

    assertThat(paid.get("status").asString()).isEqualTo("PAID");
    assertThat(paid.get("invoiceNumber").asString()).isEqualTo("WSAI-" + STRIPE.lastSessionId());
    assertThat(paid.get("invoiceAvailable").asBoolean()).isTrue();
    assertThat(read(get("/billing")).get("balanceCents").asLong()).isEqualTo(2500);
    assertThat(ledger.count()).isEqualTo(1);
    assertThat(notifications.list(TestUsers.ADMIN))
        .singleElement()
        .extracting(Notification::title, Notification::summary)
        .containsExactly("Thank you for your payment", "25.00 EUR was added to your balance.");
  }

  @Test
  void chargesVatOnTopFromOneTaxRateAndCreditsTheAmountBeforeIt() throws Exception {
    var id = read(startTopUp(2500)).get("id").asString();
    var session = STRIPE.lastSessionId();
    startTopUp(1000);

    assertThat(STRIPE.taxRates.values())
        .singleElement()
        .satisfies(
            rate -> {
              assertThat(rate.path("display_name").asString()).isEqualTo("VAT");
              assertThat(rate.path("percentage").asDouble()).isEqualTo(20.0);
              assertThat(rate.path("inclusive").asBoolean()).isFalse();
            });
    var rate = STRIPE.taxRates.keySet().iterator().next();
    assertThat(STRIPE.checkouts.getLast()).containsEntry("line_items[0][tax_rates][0]", rate);
    assertThat(STRIPE.sessions.get(session).path("amount_total").asLong()).isEqualTo(3000);

    STRIPE.pay(session);
    post("/billing/top-ups/" + id + "/refresh");

    assertThat(read(get("/billing")).get("balanceCents").asLong()).isEqualTo(2500);
  }

  @Test
  void creditsFromASignedWebhookAndRefusesAnUnsignedOne() throws Exception {
    read(startTopUp(1500));
    var session = STRIPE.lastSessionId();
    STRIPE.pay(session);
    var event = event("checkout.session.completed", session);

    assertThat(webhook(event, "t=1,v1=forged").statusCode()).isEqualTo(400);
    assertThat(read(get("/billing")).get("balanceCents").asLong()).isZero();

    assertThat(webhook(event, signature(event)).statusCode()).isEqualTo(200);
    assertThat(webhook(event, signature(event)).statusCode()).isEqualTo(200);
    assertThat(read(get("/billing")).get("balanceCents").asLong()).isEqualTo(1500);
  }

  @Test
  void dropsAnAbandonedCheckoutFromTheList() throws Exception {
    read(startTopUp(1500));
    STRIPE.expire(STRIPE.lastSessionId());
    var event = event("checkout.session.expired", STRIPE.lastSessionId());

    webhook(event, signature(event));
    webhook(event, signature(event));

    assertThat(read(get("/billing")).get("topUps")).isEmpty();
    assertThat(notifications.list(TestUsers.ADMIN))
        .singleElement()
        .extracting(Notification::title, Notification::summary)
        .containsExactly("Top-up not completed", "A top-up of 15.00 EUR was never paid.");
  }

  @Test
  void marksAFailedAsyncPaymentFailed() throws Exception {
    read(startTopUp(1500));
    var event = event("checkout.session.async_payment_failed", STRIPE.lastSessionId());

    webhook(event, signature(event));

    assertThat(read(get("/billing")).get("topUps").get(0).get("status").asString())
        .isEqualTo("FAILED");
    assertThat(notifications.list(TestUsers.ADMIN))
        .singleElement()
        .extracting(Notification::title, Notification::summary)
        .containsExactly("Payment failed", "A top-up of 15.00 EUR did not go through.");
  }

  @Test
  void ignoresEventsItDoesNotHandle() throws Exception {
    var event = event("customer.created", "cus_1");
    assertThat(webhook(event, signature(event)).statusCode()).isEqualTo(200);
  }

  @Test
  void redirectsToTheInvoicePdfOncePaid() throws Exception {
    var id = read(startTopUp(2500)).get("id").asString();
    assertThat(get("/billing/top-ups/" + id + "/invoice").statusCode()).isEqualTo(404);

    STRIPE.pay(STRIPE.lastSessionId());
    post("/billing/top-ups/" + id + "/refresh");
    var response = get("/billing/top-ups/" + id + "/invoice");

    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue("Location"))
        .hasValue("https://pay.stripe.test/in_" + STRIPE.lastSessionId() + "/pdf");
  }

  @Test
  void refusesAnAmountOutOfRange() throws Exception {
    assertThat(startTopUp(499).statusCode()).isEqualTo(400);
    assertThat(startTopUp(100_001).statusCode()).isEqualTo(400);
    assertThat(STRIPE.checkouts).isEmpty();
  }

  @Test
  void leavesNothingPendingWhenStripeRefusesTheCheckout() throws Exception {
    STRIPE.status = 400;

    assertThat(startTopUp(1000).statusCode()).isEqualTo(502);
    STRIPE.status = 503;
    assertThat(startTopUp(1000).statusCode()).isEqualTo(503);
    assertThat(read(get("/billing")).get("topUps")).isEmpty();
  }

  @Test
  void showsNoOneElsesTopUp() throws Exception {
    var id = UUID.fromString(read(startTopUp(1000)).get("id").asString());
    var other = users.save(TestUsers.newUser("billing-other-" + UUID.randomUUID()));

    assertThatThrownBy(() -> billing.refresh(other.getId(), id))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no such top-up");
    assertThat(billing.billing(other.getId()).topUps()).isEmpty();
  }

  @Test
  void savesACardThroughASetupCheckoutAndShowsIt() throws Exception {
    var response = post("/billing/card");

    assertThat(response.statusCode()).isEqualTo(201);
    var session = STRIPE.lastSessionId();
    assertThat(read(response).get("checkoutUrl").asString())
        .isEqualTo("https://checkout.stripe.test/" + session);
    assertThat(STRIPE.checkouts.getLast())
        .containsEntry("mode", "setup")
        .containsEntry(
            "success_url", "https://ai.workspaced.test/billing?card_session={CHECKOUT_SESSION_ID}");
    assertThat(confirmCard(session).statusCode()).isEqualTo(409);

    STRIPE.saveCard(session, "pm_1", "visa", "4242");
    assertThat(confirmCard(session).statusCode()).isEqualTo(204);

    assertThat(read(get("/billing")).get("card").asString()).isEqualTo("Visa •••• 4242");
  }

  @Test
  void keepsTheCardFromTheWebhookAndDetachesTheOneItReplaces() throws Exception {
    post("/billing/card");
    STRIPE.saveCard(STRIPE.lastSessionId(), "pm_old", "visa", "4242");
    confirmCard(STRIPE.lastSessionId());
    post("/billing/card");
    var session = STRIPE.lastSessionId();
    STRIPE.saveCard(session, "pm_new", "mastercard", "4444");
    var event = setupEvent(session);

    assertThat(webhook(event, signature(event)).statusCode()).isEqualTo(200);

    assertThat(read(get("/billing")).get("card").asString()).isEqualTo("Mastercard •••• 4444");
    assertThat(STRIPE.detached).containsExactly("pm_old");
  }

  @Test
  void refusesAutomaticTopUpsWithoutACardAndTurnsThemOffWithIt() throws Exception {
    assertThat(read(get("/billing")).get("autoTopUp").get("enabled").asBoolean()).isFalse();
    assertThat(setAutoTopUp(true, 1000, 2000).statusCode()).isEqualTo(409);
    assertThat(setAutoTopUp(false, 400, 2000).statusCode()).isEqualTo(400);

    post("/billing/card");
    STRIPE.saveCard(STRIPE.lastSessionId(), "pm_1", "visa", "4242");
    confirmCard(STRIPE.lastSessionId());
    assertThat(read(setAutoTopUp(true, 1500, 3000)))
        .isEqualTo(JSON.readTree("{\"enabled\":true,\"belowCents\":1500,\"amountCents\":3000}"));

    assertThat(send(request("/billing/card").DELETE()).statusCode()).isEqualTo(204);

    var billing = read(get("/billing"));
    assertThat(billing.get("card").isNull()).isTrue();
    assertThat(billing.get("autoTopUp").get("enabled").asBoolean()).isFalse();
    assertThat(STRIPE.detached).containsExactly("pm_1");
  }

  @Test
  void chargesTheSavedCardOnceTheBalanceIsLowWithAnInvoiceForItAndVat() {
    var user = payer();

    autoTopUps.topUp(user);

    var shown = billing.billing(user);
    assertThat(shown.balanceCents()).isEqualTo(2000);
    var topUp = shown.topUps().getFirst();
    assertThat(topUp.automatic()).isTrue();
    assertThat(topUp.status()).isEqualTo(TopUpStatus.PAID);
    assertThat(topUp.invoiceNumber()).startsWith("WSAI-in_");
    var invoice = STRIPE.invoices.values().iterator().next();
    assertThat(invoice.get("status").asString()).isEqualTo("paid");
    assertThat(invoice.get("subtotal").asLong()).isEqualTo(2000);
    assertThat(invoice.get("total").asLong()).isEqualTo(2400);
    assertThat(invoice.get("default_payment_method").asString()).isEqualTo("pm_payer");

    // Above the threshold now, so nothing more is charged.
    autoTopUps.topUp(user);
    assertThat(billing.billing(user).topUps()).hasSize(1);
  }

  @Test
  void turnsAutomaticTopUpsOffAndSaysWhyWhenTheCardIsDeclined() {
    var user = payer();
    STRIPE.declineCards = true;

    autoTopUps.topUp(user);

    var shown = billing.billing(user);
    assertThat(shown.balanceCents()).isZero();
    assertThat(shown.topUps().getFirst().status()).isEqualTo(TopUpStatus.FAILED);
    assertThat(shown.autoTopUp().enabled()).isFalse();
    assertThat(STRIPE.invoices.values().iterator().next().get("status").asString())
        .isEqualTo("void");
    assertThat(notifications.list(user).getFirst())
        .extracting(Notification::title, Notification::body)
        .satisfies(
            fields -> {
              assertThat(fields.get(0)).isEqualTo("Automatic top-up failed");
              assertThat((String) fields.get(1)).contains("Your card was declined.");
            });
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer, atLeast(0)).send(mail.capture());
    assertThat(mail.getAllValues())
        .singleElement()
        .satisfies(
            sent -> {
              assertThat(sent.subject()).isEqualTo("Automatic top-up failed");
              assertThat(sent.text()).contains("Your card was declined.", "[Go to billing](");
            });

    STRIPE.declineCards = false;
    autoTopUps.topUp(user);
    assertThat(billing.billing(user).topUps()).hasSize(1);
  }

  @Test
  void triesACardChargeStripeCouldNotTakeAgainAndCreditsItOnce() {
    var user = payer();
    STRIPE.status = 503;

    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    assertThat(billing.billing(user).topUps().getFirst().status()).isEqualTo(TopUpStatus.PENDING);

    STRIPE.status = 0;
    autoTopUps.topUp(user);
    autoTopUps.topUp(user);

    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
    assertThat(billing.billing(user).topUps()).hasSize(1);
    assertThat(STRIPE.invoices).hasSize(1);
  }

  @Test
  void neverChargesAnAdminsCard() throws Exception {
    post("/billing/card");
    STRIPE.saveCard(STRIPE.lastSessionId(), "pm_1", "visa", "4242");
    confirmCard(STRIPE.lastSessionId());
    setAutoTopUp(true, 1000, 2000);

    autoTopUps.run();

    assertThat(STRIPE.invoices).isEmpty();
    assertThat(read(get("/billing")).get("topUps")).isEmpty();
  }

  @Test
  void recoversAPaidAutomaticTopUpAfterTheResponseIsLostAndAutomaticTopUpsAreDisabled() {
    var user = payer();
    STRIPE.losePaymentResponse = true;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    assertThat(billing.billing(user).balanceCents()).isZero();
    assertThat(STRIPE.invoices.values().iterator().next().path("status").asString())
        .isEqualTo("paid");
    billing.setAutoTopUp(user, new AutoTopUp(false, 1000, 2000));

    autoTopUps.run();
    autoTopUps.run();

    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
    assertThat(billing.billing(user).autoTopUp().enabled()).isFalse();
    assertThat(STRIPE.invoices).hasSize(1);
    assertThat(billing.billing(user).topUps().getFirst().status()).isEqualTo(TopUpStatus.PAID);
  }

  @Test
  void invoicePaidWebhookRecoversAfterCardRemovalAndIsIdempotent() {
    var user = payer();
    STRIPE.losePaymentResponse = true;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    billing.removeCard(user);
    var invoice = STRIPE.invoices.values().iterator().next();
    var payload =
        ("{\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\""
                + invoice.path("id").asString()
                + "\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    var timestamp = Instant.now().getEpochSecond();
    var signature =
        "t="
            + timestamp
            + ",v1="
            + new String(
                StripeSignature.sign(timestamp, payload, WEBHOOK_SECRET),
                StandardCharsets.US_ASCII);
    assertThat(billing.handleEvent(payload, signature)).isTrue();
    assertThat(billing.handleEvent(payload, signature)).isTrue();
    autoTopUps.run();
    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
    assertThat(STRIPE.invoices).hasSize(1);
  }

  @Test
  void recoversAfterCardRemovalWithoutAWebhookAndDoesNotStartAnotherCharge() {
    var user = payer();
    STRIPE.losePaymentResponse = true;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    billing.removeCard(user);
    autoTopUps.run();
    autoTopUps.run();
    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
    assertThat(STRIPE.invoices).hasSize(1);
  }

  @Test
  void doesNotChargeAPendingTopUpWhenAutomaticTopUpsAreDisabled() {
    var user = payer();
    STRIPE.status = 503;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    STRIPE.status = 0;
    billing.setAutoTopUp(user, new AutoTopUp(false, 1000, 2000));
    autoTopUps.run();
    assertThat(STRIPE.invoices).isEmpty();
    assertThat(billing.billing(user).balanceCents()).isZero();
  }

  @Test
  void recoversAcrossInvoicePagesAndRejectsAnInvoiceOfAnotherCustomer() {
    var user = payer();
    STRIPE.losePaymentResponse = true;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    var invoice = STRIPE.invoices.values().iterator().next();
    var originalCustomer = invoice.path("customer").asString();
    var topUp = billing.billing(user).topUps().getFirst();
    // One earlier, unrelated invoice forces recovery onto the next page.
    var unrelated = invoice.deepCopy();
    unrelated.put("id", "in_000");
    unrelated.putObject("metadata").put("top_up_id", UUID.randomUUID().toString());
    STRIPE.invoices.put("in_000", unrelated);
    STRIPE.invoicePageSize = 1;
    assertThat(stripe.findAutomaticInvoice(originalCustomer, topUp.id())).isPresent();

    invoice.put("customer", "cus_someone_else");
    var payload = invoiceEvent(invoice.path("id").asString());
    assertThat(billing.handleEvent(payload, signed(payload))).isTrue();
    assertThat(billing.billing(user).balanceCents()).isZero();
    invoice.put("customer", originalCustomer);
    billing.setAutoTopUp(user, new AutoTopUp(false, 1000, 2000));
    autoTopUps.run();
    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
  }

  @Test
  void doesNotCreditAnAutomaticInvoiceWithTheWrongAmountOrCurrency() {
    var user = payer();
    STRIPE.losePaymentResponse = true;
    assertThatThrownBy(() -> autoTopUps.topUp(user))
        .isInstanceOf(PaymentsUnavailableException.class);
    var invoice = STRIPE.invoices.values().iterator().next();
    var payload = invoiceEvent(invoice.path("id").asString());
    invoice.put("total", 1);
    assertThatThrownBy(() -> billing.handleEvent(payload, signed(payload)))
        .isInstanceOf(StripeFailedException.class);
    assertThat(billing.billing(user).balanceCents()).isZero();
    invoice.put("total", 2400);
    invoice.put("currency", "usd");
    assertThatThrownBy(() -> billing.handleEvent(payload, signed(payload)))
        .isInstanceOf(StripeFailedException.class);
    assertThat(billing.billing(user).balanceCents()).isZero();
    invoice.put("currency", "eur");
    assertThat(billing.handleEvent(payload, signed(payload))).isTrue();
    assertThat(billing.billing(user).balanceCents()).isEqualTo(2000);
  }

  private static byte[] invoiceEvent(String id) {
    return ("{\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"" + id + "\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String signed(byte[] payload) {
    var timestamp = Instant.now().getEpochSecond();
    return "t="
        + timestamp
        + ",v1="
        + new String(
            StripeSignature.sign(timestamp, payload, WEBHOOK_SECRET), StandardCharsets.US_ASCII);
  }

  /** A user who is not an admin, with a saved card and automatic top-ups of 20 below 10. */
  private UUID payer() {
    var user = users.save(TestUsers.newUser("billing-payer-" + UUID.randomUUID())).getId();
    billing.startCardSetup(user);
    STRIPE.saveCard(STRIPE.lastSessionId(), "pm_payer", "visa", "4242");
    billing.confirmCard(user, STRIPE.lastSessionId());
    billing.setAutoTopUp(user, new AutoTopUp(true, 1000, 2000));
    return user;
  }

  private static byte[] setupEvent(String sessionId) {
    return ("{\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\""
            + sessionId
            + "\",\"mode\":\"setup\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private HttpResponse<String> confirmCard(String sessionId) throws Exception {
    return send(
        request("/billing/card/confirm")
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString("{\"sessionId\":\"" + sessionId + "\"}")));
  }

  private HttpResponse<String> setAutoTopUp(boolean enabled, long below, long amount)
      throws Exception {
    return send(
        request("/billing/auto-top-up")
            .header("Content-Type", "application/json")
            .PUT(
                BodyPublishers.ofString(
                    "{\"enabled\":"
                        + enabled
                        + ",\"belowCents\":"
                        + below
                        + ",\"amountCents\":"
                        + amount
                        + "}")));
  }

  private static byte[] event(String type, String objectId) {
    return ("{\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + objectId + "\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String signature(byte[] payload) {
    var now = Instant.now().getEpochSecond();
    return "t="
        + now
        + ",v1="
        + new String(StripeSignature.sign(now, payload, WEBHOOK_SECRET), StandardCharsets.US_ASCII);
  }

  private HttpResponse<String> startTopUp(long cents) throws Exception {
    return send(
        request("/billing/top-ups")
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString("{\"amountCents\":" + cents + "}")));
  }

  private HttpResponse<String> webhook(byte[] payload, String signature) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(StripeWebhookController.PATH))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", signature)
            .POST(BodyPublishers.ofByteArray(payload))
            .build(),
        BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(request(path).GET());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return send(request(path).POST(BodyPublishers.noBody()));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(uri(path)).header("Authorization", TestUsers.bearer(port));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.build(), BodyHandlers.ofString());
  }

  private static JsonNode read(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}

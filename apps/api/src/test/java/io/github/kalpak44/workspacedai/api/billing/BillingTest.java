package io.github.kalpak44.workspacedai.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
  @Autowired JdbcTemplate jdbc;
  @Autowired BillingService billing;
  @Autowired UserRepository users;

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
    jdbc.update("DELETE FROM billing_ledger_entry");
    jdbc.update("DELETE FROM billing_top_up");
    jdbc.update("DELETE FROM billing_account");
    STRIPE.status = 0;
  }

  @Test
  void startsWithAnEmptyBalance() throws Exception {
    var body = read(get("/billing"));
    assertThat(body.get("balanceCents").asLong()).isZero();
    assertThat(body.get("currency").asString()).isEqualTo("eur");
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
        .containsEntry("client_reference_id", id)
        .containsEntry(
            "success_url", "https://ai.workspaced.test/billing?top_up=" + id + "&outcome=success");
    assertThat(STRIPE.idempotencyKeys.getLast()).isEqualTo(id);
    assertThat(STRIPE.customers.getLast()).containsEntry("name", "admin");

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
    assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_ledger_entry", Long.class))
        .isEqualTo(1);
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

    assertThat(read(get("/billing")).get("topUps")).isEmpty();
  }

  @Test
  void marksAFailedAsyncPaymentFailed() throws Exception {
    read(startTopUp(1500));
    var event = event("checkout.session.async_payment_failed", STRIPE.lastSessionId());

    webhook(event, signature(event));

    assertThat(read(get("/billing")).get("topUps").get(0).get("status").asString())
        .isEqualTo("FAILED");
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
    var other = users.save(new UserEntity("billing-other-" + UUID.randomUUID(), "{noop}x"));

    assertThatThrownBy(() -> billing.refresh(other.getId(), id))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no such top-up");
    assertThat(billing.billing(other.getId()).topUps()).isEmpty();
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

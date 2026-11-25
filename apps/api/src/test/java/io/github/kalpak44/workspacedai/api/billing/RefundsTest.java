package io.github.kalpak44.workspacedai.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.Attribution;
import io.github.kalpak44.workspacedai.api.auth.Auth0Users;
import io.github.kalpak44.workspacedai.api.auth.AuthService;
import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A refunded or disputed top-up takes back its balance and every reward paid on it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class RefundsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeStripe STRIPE = new FakeStripe();
  private static final String WEBHOOK_SECRET = "whsec_refunds";

  @LocalServerPort int port;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired BillingService billing;
  @Autowired AutoTopUps autoTopUps;
  @Autowired NotificationService notifications;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();
  private UserEntity referrer;
  private UserEntity friend;

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
  void setUp() throws Exception {
    STRIPE.reset();
    referrer = users.save(TestUsers.newUser("referrer-" + UUID.randomUUID() + "@example.com"));
    var code = get("/account/referrals", referrer).get("code").asString();
    var email = "friend-" + UUID.randomUUID() + "@example.com";
    auth.signIn(
        TestUsers.subject(email),
        new Auth0Users.Profile(email, "", "", ""),
        true,
        Role.USER,
        new Attribution(null, null, code));
    friend = users.findByIdpSubject(TestUsers.subject(email)).orElseThrow();
  }

  @Test
  void takesBackTheTopUpAndBothRewardsOnAFullRefund() throws Exception {
    var session = topUp(friend, 2500);
    assertThat(balance(friend)).isEqualTo(2750);
    assertThat(balance(referrer)).isEqualTo(250);

    // 30.00 with VAT, all of it refunded.
    STRIPE.refund(STRIPE.chargeOf(session), 3000);
    event("charge.refunded", STRIPE.chargeOf(session), null);

    assertThat(balance(friend)).isZero();
    assertThat(balance(referrer)).isZero();
    assertThat(notifications.list(friend.getId()))
        .extracting(Notification::title)
        .contains("A payment was refunded", "A referral credit was taken back");
    var referrals = get("/account/referrals", referrer);
    assertThat(referrals.get("earnedCents").asLong()).isZero();
  }

  @Test
  void takesBackEachPartialRefundOnceInProportion() throws Exception {
    var session = topUp(friend, 2500);
    var charge = STRIPE.chargeOf(session);

    STRIPE.refund(charge, 1200);
    event("charge.refunded", charge, null);
    // Stripe redelivers events; the same refund takes nothing more.
    event("charge.refunded", charge, null);
    assertThat(balance(friend)).isEqualTo(2750 - 1100);
    assertThat(balance(referrer)).isEqualTo(150);

    STRIPE.refund(charge, 1800);
    event("charge.refunded", charge, null);
    assertThat(balance(friend)).isZero();
    assertThat(balance(referrer)).isZero();
  }

  @Test
  void takesBackADisputedTopUpWhole() throws Exception {
    var session = topUp(friend, 2500);
    STRIPE.dispute(STRIPE.chargeOf(session));

    event("charge.dispute.created", "dp_1", STRIPE.chargeOf(session));

    assertThat(balance(friend)).isZero();
    assertThat(balance(referrer)).isZero();
  }

  @Test
  void findsAnAutomaticTopUpByItsInvoice() throws Exception {
    billing.startCardSetup(friend.getId());
    STRIPE.saveCard(STRIPE.lastSessionId(), "pm_friend", "visa", "4242");
    billing.confirmCard(friend.getId(), STRIPE.lastSessionId());
    billing.setAutoTopUp(friend.getId(), new AutoTopUp(true, 1000, 2000));
    autoTopUps.topUp(friend.getId());
    assertThat(balance(friend)).isEqualTo(2200);
    var invoice = STRIPE.invoices.keySet().iterator().next();

    STRIPE.refund(STRIPE.chargeOf(invoice), 2400);
    event("charge.refunded", STRIPE.chargeOf(invoice), null);

    assertThat(balance(friend)).isZero();
    assertThat(balance(referrer)).isZero();
  }

  @Test
  void ignoresARefundOfAChargeThatPaidNoTopUp() throws Exception {
    topUp(friend, 2500);

    assertThat(event("charge.refunded", "ch_unknown", null).statusCode()).isEqualTo(200);
    assertThat(balance(friend)).isEqualTo(2750);
  }

  private String topUp(UserEntity user, long cents) throws Exception {
    var id =
        JSON.readTree(
                send("POST", "/billing/top-ups", user, "{\"amountCents\":" + cents + "}").body())
            .get("id")
            .asString();
    var session = STRIPE.lastSessionId();
    STRIPE.pay(session);
    send("POST", "/billing/top-ups/" + id + "/refresh", user, null);
    return session;
  }

  private HttpResponse<String> event(String type, String objectId, String charge) throws Exception {
    var object =
        "{\"id\":\""
            + objectId
            + "\""
            + (charge == null ? "" : ",\"charge\":\"" + charge + "\"")
            + "}";
    var payload =
        ("{\"type\":\"" + type + "\",\"data\":{\"object\":" + object + "}}")
            .getBytes(StandardCharsets.UTF_8);
    var now = Instant.now().getEpochSecond();
    var signature =
        "t="
            + now
            + ",v1="
            + new String(
                StripeSignature.sign(now, payload, WEBHOOK_SECRET), StandardCharsets.US_ASCII);
    return http.send(
        HttpRequest.newBuilder(uri(StripeWebhookController.PATH))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", signature)
            .POST(BodyPublishers.ofByteArray(payload))
            .build(),
        BodyHandlers.ofString());
  }

  private long balance(UserEntity user) throws Exception {
    return get("/billing", user).get("balanceCents").asLong();
  }

  private JsonNode get(String path, UserEntity user) throws Exception {
    return JSON.readTree(send("GET", path, user, null).body());
  }

  private HttpResponse<String> send(String method, String path, UserEntity user, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", TestUsers.bearer(user))
            .header("Content-Type", "application/json")
            .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
    var response = http.send(request.build(), BodyHandlers.ofString());
    assertThat(response.statusCode()).as(method + " " + path).isLessThan(300);
    return response;
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }
}

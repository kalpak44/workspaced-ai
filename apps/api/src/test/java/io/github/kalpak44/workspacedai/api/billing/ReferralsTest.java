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
import java.net.http.HttpResponse.BodyHandlers;
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

/** A friend invited with a link pays, and both sides are credited, within their caps. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class ReferralsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeStripe STRIPE = new FakeStripe();

  @LocalServerPort int port;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired NotificationService notifications;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();
  private UserEntity referrer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("workspaced.billing.stripe.url", STRIPE::url);
    registry.add("workspaced.billing.stripe.secret-key", () -> FakeStripe.SECRET_KEY);
    registry.add("workspaced.billing.return-url", () -> "https://ai.workspaced.test/billing");
  }

  @AfterAll
  static void stop() {
    STRIPE.close();
  }

  @BeforeEach
  void setUp() {
    STRIPE.reset();
    referrer = users.save(TestUsers.newUser("referrer-" + UUID.randomUUID() + "@example.com"));
  }

  @Test
  void givesEachUserALinkWithTheirOwnCodeAndKeepsIt() throws Exception {
    var first = get("/account/referrals", referrer);
    var code = first.get("code").asString();

    assertThat(code).matches("[a-hj-km-np-z2-9]{8}");
    assertThat(first.get("link").asString()).isEqualTo("https://workspaced.online/?ref=" + code);
    assertThat(first.get("eligible").asBoolean()).isTrue();
    assertThat(first.get("invited").asInt()).isZero();
    assertThat(first.get("referrer").get("capCents").asLong()).isEqualTo(5000);
    assertThat(get("/account/referrals", referrer).get("code").asString()).isEqualTo(code);
  }

  @Test
  void leavesAnAdminOutOfTheProgram() throws Exception {
    var admin = users.findById(TestUsers.ADMIN).orElseThrow();
    var referrals = get("/account/referrals", admin);

    assertThat(referrals.get("eligible").asBoolean()).isFalse();
    assertThat(referrals.get("code").isNull()).isTrue();
  }

  @Test
  void creditsBothSidesOnTheFriendsPaidTopUpsUpToTheirCaps() throws Exception {
    var friend = signUp(get("/account/referrals", referrer).get("code").asString());
    var invited = get("/account/referrals", referrer);
    assertThat(invited.get("invited").asInt()).isEqualTo(1);
    assertThat(invited.get("friends").get(0).get("status").asString()).isEqualTo("SIGNED_UP");

    topUp(friend, 2500);
    assertThat(balance(friend)).isEqualTo(2500 + 250);
    assertThat(balance(referrer)).isEqualTo(250);
    assertThat(notifications.list(referrer.getId()))
        .extracting(Notification::title)
        .contains("You earned a referral reward");

    // The friend's bonus stops at 10.00 and the reward keeps its 10%.
    topUp(friend, 10_000);
    topUp(friend, 10_000);
    assertThat(balance(friend)).isEqualTo(22_500 + 1000);
    assertThat(balance(referrer)).isEqualTo(2250);

    var paying = get("/account/referrals", referrer);
    assertThat(paying.get("paying").asInt()).isEqualTo(1);
    assertThat(paying.get("earnedCents").asLong()).isEqualTo(2250);
    assertThat(paying.get("friends").get(0).get("status").asString()).isEqualTo("EARNING");
    assertThat(paying.get("friends").get(0).get("firstPaidAt").isNull()).isFalse();

    // 50.00 is the most one friend ever earns the referrer.
    topUp(friend, 100_000);
    assertThat(balance(referrer)).isEqualTo(5000);
    assertThat(get("/account/referrals", referrer).get("friends").get(0).get("status").asString())
        .isEqualTo("FINISHED");
  }

  @Test
  void bindsNothingForACodeNobodyHas() throws Exception {
    var stranger = signUp("nosuchcd");
    topUp(stranger, 2500);

    assertThat(balance(stranger)).isEqualTo(2500);
    assertThat(get("/account/referrals", referrer).get("invited").asInt()).isZero();
  }

  @Test
  void creditsARewardOnceHoweverOftenTheTopUpIsRefreshed() throws Exception {
    var friend = signUp(get("/account/referrals", referrer).get("code").asString());
    var id = topUp(friend, 2500);
    send("POST", "/billing/top-ups/" + id + "/refresh", friend);
    send("POST", "/billing/top-ups/" + id + "/refresh", friend);

    assertThat(balance(referrer)).isEqualTo(250);
    assertThat(balance(friend)).isEqualTo(2500 + 250);
    assertThat(get("/account/referrals", referrer).get("earnedCents").asLong()).isEqualTo(250);
  }

  /** As the success handler of a sign-up from a referral link would. */
  private UserEntity signUp(String code) {
    var email = "friend-" + UUID.randomUUID() + "@example.com";
    var subject = TestUsers.subject(email);
    auth.signIn(
        subject,
        new Auth0Users.Profile(email, "", "", ""),
        true,
        Role.USER,
        new Attribution(null, null, code));
    return users.findByIdpSubject(subject).orElseThrow();
  }

  private String topUp(UserEntity user, long cents) throws Exception {
    var id =
        JSON.readTree(
                send("POST", "/billing/top-ups", user, "{\"amountCents\":" + cents + "}").body())
            .get("id")
            .asString();
    STRIPE.pay(STRIPE.lastSessionId());
    send("POST", "/billing/top-ups/" + id + "/refresh", user);
    return id;
  }

  private long balance(UserEntity user) throws Exception {
    return get("/billing", user).get("balanceCents").asLong();
  }

  private JsonNode get(String path, UserEntity user) throws Exception {
    return JSON.readTree(send("GET", path, user).body());
  }

  private java.net.http.HttpResponse<String> send(String method, String path, UserEntity user)
      throws Exception {
    return send(method, path, user, null);
  }

  private java.net.http.HttpResponse<String> send(
      String method, String path, UserEntity user, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", TestUsers.bearer(user))
            .header("Content-Type", "application/json")
            .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
    var response = http.send(request.build(), BodyHandlers.ofString());
    assertThat(response.statusCode()).as(method + " " + path).isLessThan(300);
    return response;
  }
}

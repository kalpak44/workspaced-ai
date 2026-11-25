package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.FakeAuth0Users;
import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.recipe.StarterRecipes;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class AuthTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired AuthService auth;
  @Autowired Auth0Tokens tokens;
  @Autowired TokenService tokenService;
  @Autowired JwtDecoder decoder;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transaction;
  @Autowired TourEventRepository tourEvents;
  @MockitoSpyBean StarterRecipes starterRecipes;
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean PasswordResets passwordResets;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    workspaces.deleteAll();
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(keyCheck.accepts(any(), any())).thenReturn(true);
    // The store keeps whichever token expires last, so one test's token would outlive into the
    // next.
    transaction.executeWithoutResult(
        _ -> {
          var delete =
              entityManager.getCriteriaBuilder().createCriteriaDelete(Auth0TokenEntity.class);
          delete.from(Auth0TokenEntity.class);
          entityManager.createQuery(delete).executeUpdate();
        });
  }

  @Test
  void handsTheCielaRecipeOverOnlyOnceThePublishersEmailIsVerified() {
    var profile = new Auth0Users.Profile("pavel.usanli@gmail.com", "Pavel", "Usanli", "");
    assertThatThrownBy(() -> auth.signIn("publisher", profile, false, Role.USER, Attribution.NONE))
        .isInstanceOf(AuthService.UnverifiedEmailException.class);
    verify(starterRecipes, never()).claimPublisher(any(), any());

    auth.signIn("publisher", profile, true, Role.USER, Attribution.NONE);
    var owner = users.findByIdpSubject("publisher").orElseThrow().getId();
    verify(starterRecipes).claimPublisher(owner, "pavel.usanli@gmail.com");
  }

  @Test
  void startsSigningInAtAuth0() throws Exception {
    var response = send("GET", "/oauth2/authorization/auth0", null, null);
    assertThat(response.statusCode()).isEqualTo(302);
    var location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .startsWith("https://auth.example.test/authorize?")
        .contains("response_type=code", "client_id=test-client", "state=", "nonce=")
        .contains("redirect_uri=http://localhost:" + port + "/api/v1/login/oauth2/code/auth0")
        .doesNotContain("acr_values");
    // The request in flight travels in a cookie, so no instance keeps a session for it.
    assertThat(response.headers().allValues("Set-Cookie"))
        .anySatisfy(c -> assertThat(c).startsWith("__Host-workspaced_sign_in="))
        .noneSatisfy(c -> assertThat(c).startsWith("JSESSIONID="));
  }

  @Test
  void asksAuth0ForMfaWhenEnrolling() throws Exception {
    var response = send("GET", "/oauth2/authorization/auth0?mfa=enroll", null, null);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).contains("acr_values="));
  }

  @Test
  void opensAuth0OnItsSignUpFormWhenAsked() throws Exception {
    var signUp = send("GET", "/oauth2/authorization/auth0?signup", null, null);
    assertThat(signUp.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).contains("screen_hint=signup"));
    var signIn = send("GET", "/oauth2/authorization/auth0", null, null);
    assertThat(signIn.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).doesNotContain("screen_hint"));
  }

  @Test
  void keepsTheSignUpSourceFromAuth0() throws Exception {
    var response =
        send(
            "GET",
            "/oauth2/authorization/auth0?signup&utm_source=newsletter&utm_campaign=nov",
            null,
            null);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).doesNotContain("utm_", "newsletter"));
  }

  @Test
  void recordsWhereTheAccountCameFromOnlyWhenItIsMade() {
    auth.signIn(
        "test|grace@example.com",
        profile("grace@example.com"),
        true,
        Role.USER,
        new Attribution("newsletter", "nov"));
    auth.signIn(
        "test|grace@example.com",
        profile("grace@example.com"),
        true,
        Role.USER,
        new Attribution("elsewhere", null));
    var user = users.findByIdpSubject("test|grace@example.com").orElseThrow();
    assertThat(user.getSignupSource()).isEqualTo("newsletter");
    assertThat(user.getSignupCampaign()).isEqualTo("nov");
  }

  @Test
  void savesTheActiveTourPayloadBetweenBrowserRequests() throws Exception {
    var bearer = bearerFor("tour-browser@example.com");
    var response =
        send(
            "PUT",
            "/account/tour",
            bearer,
            "{\"step\":13,\"workspaceId\":\"demo\",\"sessionId\":\"page\",\"paused\":false}");
    assertThat(response.statusCode()).isEqualTo(204);
    var account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.get("tour").get("step").asInt()).isEqualTo(13);
    assertThat(account.get("tour").get("workspaceId").asString()).isEqualTo("demo");
    assertThat(account.get("tour").get("paused").asBoolean()).isFalse();
  }

  @Test
  void recordsEachMoveOfTheTour() {
    var user =
        auth.signIn(
            "test|tour@example.com",
            profile("tour@example.com"),
            true,
            Role.USER,
            Attribution.NONE);
    var id = users.findByIdpSubject("test|tour@example.com").orElseThrow().getId();
    auth.saveTour(id, new TourProgress(0, null, null, null, null, false));
    auth.saveTour(id, new TourProgress(0, null, null, null, null, false));
    auth.saveTour(id, new TourProgress(1, null, null, null, null, false));
    auth.saveTour(id, new TourProgress(1, null, null, null, null, true));
    auth.saveTour(id, new TourProgress(1, null, null, null, null, false));
    auth.saveTour(id, new TourProgress(2, null, null, null, null, false));
    auth.saveTour(id, null);
    assertThat(user.created()).isTrue();
    assertThat(
            tourEvents.findByUserIdInOrderByIdAsc(List.of(id)).stream()
                .map(event -> event.getStep() + ":" + event.getKind()))
        .containsExactly("0:REACHED", "1:REACHED", "1:PAUSED", "1:RESUMED", "2:REACHED", "2:ENDED");
  }

  @Test
  void sendsAFailedSignInBackToTheLoginPage() throws Exception {
    var response =
        send("GET", "/login/oauth2/code/auth0?error=access_denied&state=unknown", null, null);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/login?error=failed"));
  }

  @Test
  void matchesTheAccountByItsAuth0SubjectAndKeepsNoProfile() throws Exception {
    var first =
        auth.signIn(
            "test|ada@example.com", profile("ada@example.com"), true, Role.USER, Attribution.NONE);
    assertThat(first.created()).isTrue();
    var again =
        auth.signIn(
            "test|ada@example.com", profile("ada@example.com"), true, Role.USER, Attribution.NONE);
    assertThat(again.created()).isFalse();

    // The profile shown is the one Auth0 vouched for at sign-in, carried in the token.
    var account =
        JSON.readTree(send("GET", "/account", "Bearer " + again.token().value(), null).body());
    assertThat(account.get("email").asString()).isEqualTo("ada@example.com");
    assertThat(account.get("avatar").asString()).isEqualTo("https://pictures.test/ada@example.com");
  }

  @Test
  void takesTheRoleAuth0AssignsAtEachSignIn() {
    auth.signIn("auth0|ada", profile("ada@example.com"), true, Role.ADMIN, Attribution.NONE);
    assertThat(users.findByIdpSubject("auth0|ada"))
        .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo(Role.ADMIN));
    auth.signIn("auth0|ada", profile("ada@example.com"), true, Role.USER, Attribution.NONE);
    assertThat(users.findByIdpSubject("auth0|ada"))
        .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo(Role.USER));
  }

  @Test
  void keepsTheManagementTokenThatExpiresLast() {
    var later = Instant.now().plusSeconds(7200);
    tokens.save(new Auth0Tokens.Token("later", later));
    tokens.save(new Auth0Tokens.Token("sooner", Instant.now().plusSeconds(3600)));
    assertThat(tokens.current()).hasValueSatisfying(t -> assertThat(t.value()).isEqualTo("later"));

    tokens.save(new Auth0Tokens.Token("latest", later.plusSeconds(60)));
    assertThat(tokens.current()).hasValueSatisfying(t -> assertThat(t.value()).isEqualTo("latest"));
  }

  @Test
  void dropsARefusedTokenOnlyWhileItIsStillTheStoredOne() {
    tokens.save(new Auth0Tokens.Token("refused", Instant.now().plusSeconds(3600)));
    tokens.discard("already-replaced");
    assertThat(tokens.current()).isPresent();

    tokens.discard("refused");
    assertThat(tokens.current()).isEmpty();
  }

  @Test
  void readsTheAdminRoleFromTheIdTokensRolesClaim() {
    assertThat(Auth0SignIn.role(oidcUser(List.of("admin")))).isEqualTo(Role.ADMIN);
    assertThat(Auth0SignIn.role(oidcUser(List.of("support")))).isEqualTo(Role.USER);
    assertThat(Auth0SignIn.role(oidcUser(null))).isEqualTo(Role.USER);
  }

  @Test
  void welcomesANewAccountByMailFromTheNoReplyAddress() {
    auth.sendWelcomeMail("ada@example.com");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    assertThat(mail.getValue().from().email()).startsWith("noreply@");
    assertThat(mail.getValue().subject()).isEqualTo("Welcome to workspaced");
    assertThat(mail.getValue().text()).contains("(https://ai.example.test/?tour)");
    assertThat(mail.getValue().html()).contains("href=\"https://ai.example.test/?tour\"");
  }

  @Test
  void signsInEvenWhenTheWelcomeMailCannotGoOut() {
    when(mailer.send(any())).thenThrow(new IllegalStateException("Mailjet is down"));
    assertThatCode(() -> auth.sendWelcomeMail("ada@example.com")).doesNotThrowAnyException();
  }

  @Test
  void refusesAnEmailAuth0HasNotVerified() {
    assertThatThrownBy(
            () ->
                auth.signIn(
                    "auth0|ada", profile("ada@example.com"), false, Role.USER, Attribution.NONE))
        .isInstanceOf(AuthService.UnverifiedEmailException.class);
    assertThat(users.findByIdpSubject(TestUsers.subject("ada@example.com"))).isEmpty();
  }

  @Test
  void turnsAwayRequestsWithoutAValidToken() throws Exception {
    var anonymous = send("GET", "/workspaces", null, null);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(send("GET", "/workspaces", "Bearer not-a-jwt", null).statusCode()).isEqualTo(401);
    assertThat(sendWithCookie("/account", "not-a-jwt").statusCode()).isEqualTo(401);
  }

  @Test
  void acceptsTheTokenAsACookie() throws Exception {
    var token = TestUsers.token(TestUsers.ADMIN, TestUsers.subject(TestUsers.ADMIN_EMAIL));
    var account = sendWithCookie("/account", token);
    assertThat(account.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(account.body()).get("email").asString())
        .isEqualTo(TestUsers.ADMIN_EMAIL);
  }

  @Test
  void requiresTheCsrfTokenForChangesMadeWithTheCookie() throws Exception {
    var token = TestUsers.token(TestUsers.ADMIN, TestUsers.subject(TestUsers.ADMIN_EMAIL));
    var issued = sendWithCookie("/auth/csrf", token);
    assertThat(issued.statusCode()).isEqualTo(200);
    var csrf =
        issued.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("__Host-XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    assertThat(csrf).contains("HttpOnly", "SameSite=Strict");
    var cookie = csrf.substring(0, csrf.indexOf(';'));
    var value = JSON.readTree(issued.body()).get("token").asString();
    var body = "{\"firstName\":\"\",\"lastName\":\"\",\"avatar\":\"\"}";

    var forged =
        HttpRequest.newBuilder(url("/account/profile"))
            .header("Cookie", "__Host-workspaced_token=" + token)
            .header("Content-Type", "application/json")
            .PUT(BodyPublishers.ofString(body))
            .build();
    var refused = http.send(forged, BodyHandlers.ofString());
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(refused.body()).get("detail").asString()).contains("CSRF");

    var genuine =
        HttpRequest.newBuilder(url("/account/profile"))
            .header("Cookie", "__Host-workspaced_token=" + token + "; " + cookie)
            .header("X-XSRF-TOKEN", value)
            .header("Content-Type", "application/json")
            .PUT(BodyPublishers.ofString(body))
            .build();
    assertThat(http.send(genuine, BodyHandlers.ofString()).statusCode()).isEqualTo(200);
  }

  @Test
  void aStaleCookieDoesNotBlockSigningInOrHealth() throws Exception {
    assertThat(sendWithCookie("/auth/csrf", "expired").statusCode()).isEqualTo(200);
    assertThat(sendWithCookie("/oauth2/authorization/auth0", "expired").statusCode())
        .isEqualTo(302);
    assertThat(sendWithCookie("/actuator/health/readiness", "expired").statusCode()).isEqualTo(200);
  }

  @Test
  void signingOutClearsTheCookie() throws Exception {
    var response = send("POST", "/auth/logout", null, null);
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.headers().firstValue("Set-Cookie"))
        .hasValueSatisfying(
            c ->
                assertThat(c)
                    .startsWith("__Host-workspaced_token=;")
                    .contains("Max-Age=0", "Path=/;", "Secure", "HttpOnly"));
  }

  @Test
  void endsTheAuth0SessionOnThePublicSiteAndClearsTheCookie() throws Exception {
    // Without it the next sign-in would pass without asking for a password.
    var response = send("GET", "/auth/end-session", null, null);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValue(
            "https://auth.example.test/v2/logout?client_id=test-client"
                + "&returnTo=https%3A%2F%2Fsite.example.test%2F");
    assertThat(response.headers().firstValue("Set-Cookie"))
        .hasValueSatisfying(c -> assertThat(c).startsWith("__Host-workspaced_token=;"));
  }

  @Test
  void signingOutRevokesEverySessionIssuedBeforeIt() throws Exception {
    var ada = users.save(TestUsers.newUser("ada@example.com"));
    var earlier = TestUsers.token(ada.getId(), ada.getIdpSubject(), Instant.now().minusSeconds(5));
    var elsewhere =
        TestUsers.token(ada.getId(), ada.getIdpSubject(), Instant.now().minusSeconds(5));
    assertThat(sendWithCookie("/account", earlier).statusCode()).isEqualTo(200);

    var csrf = csrf();
    var signOut =
        HttpRequest.newBuilder(url("/auth/logout"))
            .header("Cookie", "__Host-workspaced_token=" + earlier + "; " + csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token())
            .POST(BodyPublishers.noBody())
            .build();
    assertThat(http.send(signOut, BodyHandlers.ofString()).statusCode()).isEqualTo(204);

    // On every device: a copy of any token from before the sign-out is refused.
    assertThat(sendWithCookie("/account", earlier).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/account", "Bearer " + elsewhere, null).statusCode()).isEqualTo(401);
    var later = TestUsers.token(ada.getId(), ada.getIdpSubject(), Instant.now().plusSeconds(1));
    assertThat(sendWithCookie("/account", later).statusCode()).isEqualTo(200);
  }

  @Test
  void keepsTheManagementTokenEncryptedAtRest() {
    tokens.save(new Auth0Tokens.Token("management-token", Instant.now().plusSeconds(3600)));
    var stored =
        entityManager.find(Auth0TokenEntity.class, Auth0TokenEntity.SINGLETON_ID).getAccessToken();
    assertThat(stored).doesNotContain("management-token");
    assertThat(tokens.current())
        .hasValueSatisfying(t -> assertThat(t.value()).isEqualTo("management-token"));
  }

  @Test
  void refusesAChangeABrowserSentFromAnotherOrigin() throws Exception {
    var token = TestUsers.token(TestUsers.ADMIN, TestUsers.subject(TestUsers.ADMIN_EMAIL));
    var csrf = csrf();
    for (var header :
        List.of(
            new String[] {"Sec-Fetch-Site", "same-site"},
            new String[] {"Sec-Fetch-Site", "cross-site"},
            new String[] {"Origin", "https://abc.vm.workspaced.online"})) {
      var request =
          HttpRequest.newBuilder(url("/account/password-reset"))
              .header("Cookie", "__Host-workspaced_token=" + token + "; " + csrf.cookie())
              .header("X-XSRF-TOKEN", csrf.token())
              .header(header[0], header[1])
              .POST(BodyPublishers.noBody())
              .build();
      var refused = http.send(request, BodyHandlers.ofString());
      assertThat(refused.statusCode()).as(header[1]).isEqualTo(403);
      assertThat(JSON.readTree(refused.body()).get("detail").asString()).contains("this site");
    }
    verifyNoInteractions(passwordResets);
  }

  @Test
  void takesTheCsrfTokenFromTheHeaderOnlyNeverAFormField() throws Exception {
    var token = TestUsers.token(TestUsers.ADMIN, TestUsers.subject(TestUsers.ADMIN_EMAIL));
    var csrf = csrf();
    var request =
        HttpRequest.newBuilder(url("/account/password-reset"))
            .header("Cookie", "__Host-workspaced_token=" + token + "; " + csrf.cookie())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(BodyPublishers.ofString("_csrf=" + csrf.token()))
            .build();
    assertThat(http.send(request, BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    verifyNoInteractions(passwordResets);
  }

  @Test
  void resendsTheVerificationEmailOnceAMinuteForTheSignUpTheCookieNames() throws Exception {
    assertThat(resend(null).statusCode()).isEqualTo(401);

    var proof = tokenService.issueVerification("auth0|unverified-" + UUID.randomUUID());
    assertThat(resend(proof.value()).statusCode()).isEqualTo(204);
    assertThat(FakeAuth0Users.VERIFICATION_EMAILS).contains(subjectOf(proof));
    assertThat(resend(proof.value()).statusCode()).isEqualTo(429);

    // A sign-in token is no proof of a sign-up, and a proof signs no one in.
    var session = TestUsers.token(TestUsers.ADMIN, TestUsers.subject(TestUsers.ADMIN_EMAIL));
    assertThat(resend(session).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/account", "Bearer " + proof.value(), null).statusCode())
        .isEqualTo(401);
  }

  private String subjectOf(TokenService.Token token) {
    return decoder.decode(token.value()).getSubject();
  }

  private HttpResponse<String> resend(String proof) throws Exception {
    var csrf = csrf();
    var cookies = csrf.cookie() + (proof == null ? "" : "; __Host-workspaced_verify=" + proof);
    var request =
        HttpRequest.newBuilder(url("/auth/verification-email"))
            .header("Cookie", cookies)
            .header("X-XSRF-TOKEN", csrf.token())
            .POST(BodyPublishers.noBody())
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  @Test
  void mailsBalanceRemindersUntilTheUserTurnsThemOff() throws Exception {
    var bearer = bearerFor("grace@example.com");
    var account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.path("mail").path("balanceReminders").asBoolean()).isTrue();

    var updated = send("PUT", "/account/mail-preferences", bearer, "{\"balanceReminders\":false}");

    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(updated.body()).path("mail").path("balanceReminders").asBoolean())
        .isFalse();
    account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.path("mail").path("balanceReminders").asBoolean()).isFalse();
    // Left out of the update, so kept.
    assertThat(account.path("mail").path("tips").asBoolean()).isTrue();

    send("PUT", "/account/mail-preferences", bearer, "{\"tips\":false}");
    account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.path("mail").path("tips").asBoolean()).isFalse();
    assertThat(account.path("mail").path("balanceReminders").asBoolean()).isFalse();
  }

  @Test
  void savesTheNamesToAuth0() throws Exception {
    var bearer = bearerFor("ada@example.com");
    var updated =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\" Ada \",\"lastName\":\"Lovelace\"}");
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(FakeAuth0Users.profile(TestUsers.subject("ada@example.com")).givenName())
        .isEqualTo("Ada");

    // The answer carries a session cookie with the new names, so every page shows them at once.
    var cookie = updated.headers().firstValue("Set-Cookie").orElseThrow();
    assertThat(cookie).startsWith("__Host-workspaced_token=");
    var token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    var account = JSON.readTree(send("GET", "/account", "Bearer " + token, null).body());
    assertThat(account.get("firstName").asString()).isEqualTo("Ada");
    assertThat(account.get("lastName").asString()).isEqualTo("Lovelace");
  }

  @Test
  void refusesANameOverAHundredCharacters() throws Exception {
    var bearer = bearerFor("ada@example.com");
    var long_ = "a".repeat(101);
    var response =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\"" + long_ + "\",\"lastName\":\"\"}");
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void mailsAPasswordResetToTheAccountsOwnAddress() throws Exception {
    var bearer = bearerFor("ada@example.com");
    assertThat(send("POST", "/account/password-reset", bearer, null).statusCode()).isEqualTo(204);
    verify(passwordResets).send("ada@example.com");
  }

  @Test
  void keepsEachUsersWorkspacesToThemselves() throws Exception {
    var admin = TestUsers.bearer(port);
    var other = bearerFor("ada@example.com");
    var created =
        send(
            "POST",
            "/workspaces",
            admin,
            "{\"name\":\"Admin's\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    var id = UUID.fromString(JSON.readTree(created.body()).get("id").asString());

    assertThat(JSON.readTree(send("GET", "/workspaces", other, null).body())).isEmpty();
    assertThat(JSON.readTree(send("GET", "/workspaces", admin, null).body())).hasSize(1);

    // Another user's workspace answers exactly as one that does not exist.
    var rename = send("PUT", "/workspaces/" + id, other, "{\"name\":\"Mine now\"}");
    assertThat(rename.statusCode()).isEqualTo(404);
    assertThat(rename.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(send("GET", "/workspaces/" + id + "/sessions", other, null).statusCode())
        .isEqualTo(404);
    assertThat(send("GET", "/workspaces/" + id + "/secrets", other, null).statusCode())
        .isEqualTo(404);
    assertThat(send("DELETE", "/workspaces/" + id, other, null).statusCode()).isEqualTo(404);
    assertThat(send("GET", "/workspaces/" + id + "/secrets", admin, null).statusCode())
        .isEqualTo(200);
    assertThat(workspaces.findById(id))
        .hasValueSatisfying(w -> assertThat(w.getName()).isEqualTo("Admin's"));
  }

  @Test
  void aPaddedIdDoesNotSlipPastTheOwnerCheck() throws Exception {
    var admin = TestUsers.bearer(port);
    var other = bearerFor("ada@example.com");
    var created =
        send(
            "POST",
            "/workspaces",
            admin,
            "{\"name\":\"Admin's\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}");
    var id = JSON.readTree(created.body()).get("id").asString();

    // Spring MVC trims a UUID path variable before parsing it, so each of these binds to id.
    for (var padded : new String[] {"%20" + id, id + "%20", "%09" + id, "%20" + id + "%20"}) {
      assertThat(send("GET", "/workspaces/" + padded + "/secrets", other, null).statusCode())
          .as(padded)
          .isEqualTo(404);
      assertThat(send("GET", "/workspaces/" + padded + "/variables", other, null).statusCode())
          .as(padded)
          .isEqualTo(404);
      assertThat(
              send("PUT", "/workspaces/" + padded, other, "{\"name\":\"Mine now\"}").statusCode())
          .as(padded)
          .isEqualTo(404);
    }
    assertThat(send("GET", "/workspaces/%20" + id + "/secrets", admin, null).statusCode())
        .isEqualTo(200);
    assertThat(workspaces.findById(UUID.fromString(id)))
        .hasValueSatisfying(w -> assertThat(w.getName()).isEqualTo("Admin's"));
  }

  @Test
  void refusesTheTokenOfADeletedAccount() throws Exception {
    var ada = bearerFor("ada@example.com");
    assertThat(send("GET", "/workspaces", ada, null).statusCode()).isEqualTo(200);
    users.delete(users.findByIdpSubject(TestUsers.subject("ada@example.com")).orElseThrow());
    assertThat(send("GET", "/workspaces", ada, null).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/model-providers", ada, null).statusCode()).isEqualTo(401);
  }

  @Test
  void reportsTheRoleOnTheAccount() throws Exception {
    var admin = JSON.readTree(send("GET", "/account", TestUsers.bearer(port), null).body());
    assertThat(admin.get("role").asString()).isEqualTo("ADMIN");
    var ada = JSON.readTree(send("GET", "/account", bearerFor("ada@example.com"), null).body());
    assertThat(ada.get("role").asString()).isEqualTo("USER");
  }

  @Test
  void guardsTheTerminalWebSocketLikeEveryOtherRoute() throws Exception {
    var admin = TestUsers.bearer(port);
    var created =
        send(
            "POST",
            "/workspaces",
            admin,
            "{\"name\":\"Shell\",\"modelProvider\":\"openrouter\",\"apiKey\":\"sk-or-1\"}");
    var id = JSON.readTree(created.body()).get("id").asString();
    var path = "/workspaces/" + id + "/terminals/pty_1/connect";

    assertThat(handshake(path, null)).isEqualTo(401);
    assertThat(handshake(path, bearerFor("ada@example.com"))).isEqualTo(404);
    // The owner gets through to the handler; there is no harness here, so it closes at once.
    assertThat(handshake(path, admin)).isEqualTo(101);
  }

  /** The status the WebSocket upgrade answered with: 101 when it was accepted. */
  private int handshake(String path, String bearer) {
    var builder = http.newWebSocketBuilder();
    if (bearer != null) {
      builder.header("Authorization", bearer);
    }
    var uri = URI.create("ws://localhost:" + port + "/api/v1" + path);
    try {
      builder.buildAsync(uri, new java.net.http.WebSocket.Listener() {}).join().abort();
      return 101;
    } catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof java.net.http.WebSocketHandshakeException refused) {
        return refused.getResponse().statusCode();
      }
      throw e;
    }
  }

  private String bearerFor(String email) {
    return TestUsers.bearer(
        users
            .findByIdpSubject(TestUsers.subject(email))
            .orElseGet(() -> users.save(TestUsers.newUser(email))));
  }

  private static Auth0Users.Profile profile(String email) {
    return new Auth0Users.Profile(email, "", "", "https://pictures.test/" + email);
  }

  private static OidcUser oidcUser(List<String> roles) {
    var claims = new HashMap<String, Object>(Map.of("sub", "auth0|ada"));
    if (roles != null) {
      claims.put(Auth0SignIn.ROLES_CLAIM, roles);
    }
    var token = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), claims);
    return new DefaultOidcUser(List.of(), token);
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  private HttpResponse<String> sendWithCookie(String path, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(url(path))
            .header("Cookie", "__Host-workspaced_token=" + token)
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String method, String path, String bearer, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(url(path));
    if (bearer != null) {
      builder.header("Authorization", bearer);
    } else if (!SAFE_METHODS.contains(method)) {
      var csrf = csrf();
      builder.header("Cookie", csrf.cookie()).header("X-XSRF-TOKEN", csrf.token());
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    var request =
        builder
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  private Csrf csrf() throws Exception {
    var request = HttpRequest.newBuilder(url("/auth/csrf")).GET().build();
    var response = http.send(request, BodyHandlers.ofString());
    var cookie =
        response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith("__Host-XSRF-TOKEN="))
            .map(value -> value.substring(0, value.indexOf(';')))
            .findFirst()
            .orElseThrow();
    return new Csrf(cookie, JSON.readTree(response.body()).get("token").asString());
  }

  private record Csrf(String cookie, String token) {}
}

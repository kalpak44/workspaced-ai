package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @MockitoBean ApiKeyCheck keyCheck;
  @MockitoBean PasswordResets passwordResets;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    workspaces.deleteAll();
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  /** Other test classes share the database and expect the default admin as V9 made it. */
  @AfterEach
  void unlinkTheDefaultAdmin() {
    var admin = users.findById(TestUsers.ADMIN).orElseThrow();
    admin.signedInAs(null, TestUsers.ADMIN_EMAIL);
    users.save(admin);
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
  void sendsAFailedSignInBackToTheLoginPage() throws Exception {
    var response =
        send("GET", "/login/oauth2/code/auth0?error=access_denied&state=unknown", null, null);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/login?error=failed"));
  }

  @Test
  void linksAnAccountMadeBeforeAuth0ByItsVerifiedEmail() throws Exception {
    var signIn = auth.signIn("auth0|pavel", "Pavel.Usanli@gmail.com", true);
    assertThat(signIn.created()).isFalse();
    var bearer = "Bearer " + signIn.token().value();
    var account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.get("id").asString()).isEqualTo(TestUsers.ADMIN.toString());
    assertThat(account.get("role").asString()).isEqualTo("ADMIN");

    // Matched by subject from then on, so a changed address follows the account.
    auth.signIn("auth0|pavel", "pavel@example.com", true);
    assertThat(users.findById(TestUsers.ADMIN))
        .hasValueSatisfying(
            u -> {
              assertThat(u.getIdpSubject()).isEqualTo("auth0|pavel");
              assertThat(u.getEmail()).isEqualTo("pavel@example.com");
            });
  }

  @Test
  void createsAnAccountOnFirstSignIn() {
    assertThat(auth.signIn("auth0|ada", "ada@example.com", true).created()).isTrue();
    assertThat(auth.signIn("auth0|ada", "ada@example.com", true).created()).isFalse();
    auth.signIn("auth0|boss", "Boss@example.com", true);
    assertThat(users.findByIdpSubject("auth0|ada"))
        .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo(Role.USER));
    assertThat(users.findByIdpSubject("auth0|boss"))
        .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo(Role.ADMIN));
  }

  @Test
  void welcomesANewAccountByMailFromTheNoReplyAddress() {
    auth.sendWelcomeMail("ada@example.com", "https://ai.example.test/");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    assertThat(mail.getValue().from().email()).startsWith("noreply@");
    assertThat(mail.getValue().subject()).isEqualTo("Welcome to workspaced");
    assertThat(mail.getValue().text()).contains("(https://ai.example.test/)");
    assertThat(mail.getValue().html()).contains("href=\"https://ai.example.test/\"");
  }

  @Test
  void signsInEvenWhenTheWelcomeMailCannotGoOut() {
    when(mailer.send(any())).thenThrow(new IllegalStateException("Mailjet is down"));
    assertThatCode(() -> auth.sendWelcomeMail("ada@example.com", "https://ai.example.test/"))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAnEmailAuth0HasNotVerified() {
    assertThatThrownBy(() -> auth.signIn("auth0|ada", "ada@example.com", false))
        .isInstanceOf(AuthService.UnverifiedEmailException.class);
    assertThat(users.findByEmail("ada@example.com")).isEmpty();
  }

  @Test
  void neverHandsALinkedAccountToAnotherAuth0User() {
    auth.signIn("auth0|ada", "ada@example.com", true);
    assertThatThrownBy(() -> auth.signIn("auth0|mallory", "ada@example.com", true))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(users.findByIdpSubject("auth0|mallory")).isEmpty();
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
    var token = TestUsers.token(TestUsers.ADMIN, 0);
    var account = sendWithCookie("/account", token);
    assertThat(account.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(account.body()).get("email").asString())
        .isEqualTo(TestUsers.ADMIN_EMAIL);
  }

  @Test
  void requiresTheCsrfTokenForChangesMadeWithTheCookie() throws Exception {
    var token = TestUsers.token(TestUsers.ADMIN, 0);
    var issued = sendWithCookie("/auth/csrf", token);
    assertThat(issued.statusCode()).isEqualTo(200);
    var csrf =
        issued.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    assertThat(csrf).contains("HttpOnly", "SameSite=Strict");
    var cookie = csrf.substring(0, csrf.indexOf(';'));
    var value = JSON.readTree(issued.body()).get("token").asString();
    var body = "{\"firstName\":\"\",\"lastName\":\"\",\"avatar\":\"\"}";

    var forged =
        HttpRequest.newBuilder(url("/account/profile"))
            .header("Cookie", "workspaced_token=" + token)
            .header("Content-Type", "application/json")
            .PUT(BodyPublishers.ofString(body))
            .build();
    var refused = http.send(forged, BodyHandlers.ofString());
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(refused.body()).get("detail").asString()).contains("CSRF");

    var genuine =
        HttpRequest.newBuilder(url("/account/profile"))
            .header("Cookie", "workspaced_token=" + token + "; " + cookie)
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
            c -> assertThat(c).startsWith("workspaced_token=;").contains("Max-Age=0"));
  }

  @Test
  void endsTheAuth0SessionAndComesBackToTheApp() throws Exception {
    // Without it the next sign-in would pass without asking for a password.
    var response = send("GET", "/auth/end-session", null, null);
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .hasValue(
            "https://auth.example.test/v2/logout?client_id=test-client&returnTo=http%3A%2F%2Flocalhost%3A"
                + port
                + "%2F");
  }

  @Test
  void keepsTheProfileOnTheAccount() throws Exception {
    var bearer = bearerFor("ada@example.com");
    var avatar = "data:image/png;base64,iVBORw0KGgo=";
    var updated =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\" Ada \",\"lastName\":\"Lovelace\",\"avatar\":\"" + avatar + "\"}");
    assertThat(updated.statusCode()).isEqualTo(200);

    var account = JSON.readTree(send("GET", "/account", bearer, null).body());
    assertThat(account.get("firstName").asString()).isEqualTo("Ada");
    assertThat(account.get("lastName").asString()).isEqualTo("Lovelace");
    assertThat(account.get("avatar").asString()).isEqualTo(avatar);

    var removed =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\"Ada\",\"lastName\":\"\",\"avatar\":\"\"}");
    assertThat(JSON.readTree(removed.body()).get("avatar").isNull()).isTrue();
  }

  @Test
  void refusesAnAvatarThatIsNotAnImage() throws Exception {
    var bearer = bearerFor("ada@example.com");
    var response =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\"\",\"lastName\":\"\",\"avatar\":\"data:text/html;base64,PGI+\"}");
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
    users.delete(users.findByEmail("ada@example.com").orElseThrow());
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
        users.findByEmail(email).orElseGet(() -> users.save(new UserEntity(email))));
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  private HttpResponse<String> sendWithCookie(String path, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(url(path)).header("Cookie", "workspaced_token=" + token).build();
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
            .filter(value -> value.startsWith("XSRF-TOKEN="))
            .map(value -> value.substring(0, value.indexOf(';')))
            .findFirst()
            .orElseThrow();
    return new Csrf(cookie, JSON.readTree(response.body()).get("token").asString());
  }

  private record Csrf(String cookie, String token) {}
}

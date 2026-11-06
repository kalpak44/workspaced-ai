package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.provider.ApiKeyCheck;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class AuthTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired WorkspaceRepository workspaces;
  @Autowired PasswordEncoder passwords;
  @MockitoBean ApiKeyCheck keyCheck;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    workspaces.deleteAll();
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(keyCheck.accepts(any(), any())).thenReturn(true);
  }

  @Test
  void signsInWithTheDefaultAccount() throws Exception {
    var response =
        send("POST", "/auth/login", null, "{\"username\":\"admin\",\"password\":\"admin\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.get("account").get("username").asString()).isEqualTo("admin");
    assertThat(body.get("token").asString()).isNotBlank();
    assertThat(response.headers().firstValue("Set-Cookie"))
        .hasValueSatisfying(
            c ->
                assertThat(c)
                    .startsWith("workspaced_token=")
                    .contains("HttpOnly", "SameSite=Strict", "Path=/api/v1"));
  }

  @Test
  void refusesAWrongPasswordAndAnUnknownUserAlike() throws Exception {
    var wrong = send("POST", "/auth/login", null, "{\"username\":\"admin\",\"password\":\"nope\"}");
    var unknown = send("POST", "/auth/login", null, "{\"username\":\"nobody\",\"password\":\"x\"}");
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(unknown.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(wrong.body()).get("detail").asString())
        .isEqualTo(JSON.readTree(unknown.body()).get("detail").asString());
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
    var token = TestUsers.login(port, "admin", "admin");
    var account = sendWithCookie("/account", token);
    assertThat(account.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(account.body()).get("username").asString()).isEqualTo("admin");
  }

  @Test
  void aStaleCookieDoesNotBlockSigningInOrHealth() throws Exception {
    var request =
        HttpRequest.newBuilder(url("/auth/login"))
            .header("Cookie", "workspaced_token=expired")
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin\"}"))
            .build();
    assertThat(http.send(request, BodyHandlers.ofString()).statusCode()).isEqualTo(200);
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
  void keepsTheProfileOnTheAccount() throws Exception {
    var bearer = bearerFor("ada", "password1");
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
    var bearer = bearerFor("ada", "password1");
    var response =
        send(
            "PUT",
            "/account/profile",
            bearer,
            "{\"firstName\":\"\",\"lastName\":\"\",\"avatar\":\"data:text/html;base64,PGI+\"}");
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void changesThePasswordOnlyWithTheCurrentOne() throws Exception {
    var bearer = bearerFor("ada", "password1");
    assertThat(
            send(
                    "PUT",
                    "/account/password",
                    bearer,
                    "{\"currentPassword\":\"wrong\",\"newPassword\":\"password2\"}")
                .statusCode())
        .isEqualTo(422);
    assertThat(
            send(
                    "PUT",
                    "/account/password",
                    bearer,
                    "{\"currentPassword\":\"password1\",\"newPassword\":\"short\"}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "PUT",
                    "/account/password",
                    bearer,
                    "{\"currentPassword\":\"password1\",\"newPassword\":\"password2\"}")
                .statusCode())
        .isEqualTo(204);
    assertThat(TestUsers.login(port, "ada", "password2")).isNotBlank();
  }

  @Test
  void keepsEachUsersWorkspacesToThemselves() throws Exception {
    var admin = TestUsers.bearer(port);
    var other = bearerFor("ada", "password1");
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

  private String bearerFor(String username, String password) {
    if (users.findByUsername(username).isEmpty()) {
      users.save(new UserEntity(username, passwords.encode(password)));
    }
    return "Bearer " + TestUsers.login(port, username, password);
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
}

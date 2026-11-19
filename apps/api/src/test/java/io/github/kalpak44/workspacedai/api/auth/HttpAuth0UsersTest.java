package io.github.kalpak44.workspacedai.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Against a stand-in for Auth0's token endpoint, Management API and change-password endpoint. */
class HttpAuth0UsersTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server = start();
  private final AtomicInteger tokens = new AtomicInteger();
  private final List<String> patches = new CopyOnWriteArrayList<>();
  private final List<String> resets = new CopyOnWriteArrayList<>();
  private volatile boolean searchFails;

  private final String base = "http://localhost:" + server.getAddress().getPort() + "/";
  private final AuthProperties properties =
      new AuthProperties(
          "k".repeat(32),
          Duration.ofHours(1),
          base,
          "client",
          "secret",
          "Username-Password-Authentication",
          base + "api/v2/");
  // What the database does for the real thing: one token, shared by every instance.
  private final AtomicReference<Auth0Tokens.Token> stored = new AtomicReference<>();
  private final Auth0Tokens tokenStore =
      new Auth0Tokens() {
        @Override
        public Optional<Token> current() {
          return Optional.ofNullable(stored.get());
        }

        @Override
        public void save(Token token) {
          stored.set(token);
        }
      };
  private final HttpAuth0Users users = new HttpAuth0Users(properties, tokenStore, JSON);

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void readsTheProfileWithTheNamesTheSignUpFormSaved() {
    var profile = users.profile("auth0|ada").orElseThrow();
    assertThat(profile.email()).isEqualTo("ada@example.com");
    assertThat(profile.givenName()).isEqualTo("Ada");
    assertThat(profile.familyName()).isEqualTo("Lovelace");
    assertThat(profile.picture()).isEqualTo("https://pictures.test/ada");
    assertThat(users.profile("auth0|gone")).isEmpty();
  }

  @Test
  void sharesOneStoredTokenAcrossInstancesUntilItNearlyExpires() {
    users.profile("auth0|ada");
    new HttpAuth0Users(properties, tokenStore, JSON).profile("auth0|ada");
    users.updateNames("auth0|ada", "Ada", "Byron");
    assertThat(tokens).hasValue(1);

    stored.set(new Auth0Tokens.Token("token", Instant.now().plusSeconds(30)));
    users.profile("auth0|ada");
    assertThat(tokens).hasValue(2);
    assertThat(stored.get().expiresAt()).isAfter(Instant.now().plusSeconds(3600));
  }

  @Test
  void usesItsOwnTokenWhenAnotherInstanceStoredTheFirstOneAtTheSameMoment() {
    var collided =
        new Auth0Tokens() {
          @Override
          public Optional<Token> current() {
            return Optional.empty();
          }

          @Override
          public void save(Token token) {
            throw new DataIntegrityViolationException("duplicate key auth0_token_pkey");
          }
        };
    var profile = new HttpAuth0Users(properties, collided, JSON).profile("auth0|ada");
    assertThat(profile).hasValueSatisfying(p -> assertThat(p.givenName()).isEqualTo("Ada"));
  }

  @Test
  void savesNamesIntoUserMetadata() {
    users.updateNames("auth0|ada", "Ada", "Byron");
    assertThat(patches)
        .singleElement()
        .satisfies(
            body ->
                assertThat(JSON.readTree(body).path("user_metadata").path("family_name").asString())
                    .isEqualTo("Byron"));
  }

  @Test
  void looksUpAPageOfAuthorsAtOnceAndToleratesAnOutage() {
    var found = users.profiles(List.of("auth0|ada", "auth0|grace"));
    assertThat(found).containsOnlyKeys("auth0|ada", "auth0|grace");
    assertThat(found.get("auth0|grace").givenName()).isEqualTo("Grace");

    searchFails = true;
    assertThat(users.profiles(List.of("auth0|ada"))).isEmpty();
  }

  @Test
  void answersBadGatewayWhenAuth0RefusesAChange() {
    assertThatThrownBy(() -> users.updateNames("auth0|refused", "A", "B"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Auth0 did not answer");
  }

  @Test
  void hasAuth0MailThePasswordResetToTheAccountsConnection() {
    new Auth0PasswordResets(properties, JSON).send("ada@example.com");
    assertThat(resets)
        .singleElement()
        .satisfies(
            body -> {
              var sent = JSON.readTree(body);
              assertThat(sent.path("email").asString()).isEqualTo("ada@example.com");
              assertThat(sent.path("connection").asString())
                  .isEqualTo("Username-Password-Authentication");
            });
    assertThatThrownBy(() -> new Auth0PasswordResets(properties, JSON).send("refused@example.com"))
        .isInstanceOf(ResponseStatusException.class);
  }

  private HttpServer start() {
    try {
      var created = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      created.createContext("/", this::handle);
      created.start();
      return created;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    switch (exchange.getRequestMethod() + " " + path) {
      case "POST /oauth/token" -> {
        tokens.incrementAndGet();
        answer(exchange, 200, Map.of("access_token", "token", "expires_in", 86400));
      }
      case "POST /dbconnections/change_password" -> {
        resets.add(body);
        answer(exchange, body.contains("refused") ? 400 : 200, Map.of());
      }
      case "GET /api/v2/users" -> {
        if (searchFails) {
          answer(exchange, 503, Map.of());
          return;
        }
        var query =
            URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        answer(
            exchange,
            200,
            List.of(user("auth0|ada", "Ada"), user("auth0|grace", "Grace")).stream()
                .filter(u -> query.contains((String) u.get("user_id")))
                .toList());
      }
      case "GET /api/v2/users/auth0%7Cada", "GET /api/v2/users/auth0|ada" ->
          answer(exchange, 200, user("auth0|ada", "Ada"));
      case "PATCH /api/v2/users/auth0%7Cada", "PATCH /api/v2/users/auth0|ada" -> {
        patches.add(body);
        answer(exchange, 200, Map.of());
      }
      default -> answer(exchange, path.contains("refused") ? 403 : 404, Map.of());
    }
  }

  private static Map<String, Object> user(String id, String givenName) {
    var family = "Ada".equals(givenName) ? "Lovelace" : "Hopper";
    return Map.of(
        "user_id",
        id,
        "email",
        givenName.toLowerCase() + "@example.com",
        "picture",
        "https://pictures.test/" + givenName.toLowerCase(),
        "user_metadata",
        Map.of("given_name", givenName, "family_name", family));
  }

  private static void answer(HttpExchange exchange, int status, Object body) throws IOException {
    var bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}

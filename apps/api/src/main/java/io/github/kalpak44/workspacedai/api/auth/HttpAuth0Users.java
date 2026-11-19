package io.github.kalpak44.workspacedai.api.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auth0's Management API, as the application users sign in with; its grant covers reading and
 * updating users and nothing else. Names live in user_metadata, where the sign-up form puts them.
 */
@Component
public class HttpAuth0Users implements Auth0Users {

  private static final Logger LOG = LoggerFactory.getLogger(HttpAuth0Users.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // Auth0's search answers at most this many users a page.
  private static final int BATCH = 50;

  private final AuthProperties properties;
  private final Auth0Tokens tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public HttpAuth0Users(AuthProperties properties, Auth0Tokens tokens, JsonMapper json) {
    this.properties = properties;
    this.tokens = tokens;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public Optional<Profile> profile(String subject) {
    var response = send(get("users/" + encode(subject) + "?fields=email,picture,user_metadata"));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    return Optional.of(profileOf(read(response)));
  }

  @Override
  public void updateNames(String subject, String givenName, String familyName) {
    var body =
        json.writeValueAsString(
            Map.of("user_metadata", Map.of("given_name", givenName, "family_name", familyName)));
    var request =
        authorized("users/" + encode(subject))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();
    read(send(request));
  }

  /** Never fails: a recipe list still shows when Auth0 does not answer, its authors unnamed. */
  @Override
  public Map<String, Profile> profiles(Collection<String> subjects) {
    var found = new HashMap<String, Profile>();
    var all = List.copyOf(subjects);
    for (var start = 0; start < all.size(); start += BATCH) {
      var batch = all.subList(start, Math.min(start + BATCH, all.size()));
      var query =
          batch.stream()
              .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .collect(Collectors.joining(" OR ", "user_id:(", ")"));
      try {
        var users =
            read(
                send(
                    get(
                        "users?search_engine=v3&per_page="
                            + BATCH
                            + "&fields=user_id,email,picture,user_metadata&q="
                            + encode(query))));
        users.forEach(user -> found.put(user.path("user_id").asString(), profileOf(user)));
      } catch (ResponseStatusException e) {
        LOG.warn("Auth0 did not answer for {} users' names", batch.size(), e);
      }
    }
    return found;
  }

  private static Profile profileOf(JsonNode user) {
    var metadata = user.path("user_metadata");
    return new Profile(
        text(user.path("email")),
        text(metadata.path("given_name")),
        text(metadata.path("family_name")),
        text(user.path("picture")));
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.asString() : "";
  }

  private HttpRequest get(String path) {
    return authorized(path).GET().build();
  }

  private HttpRequest.Builder authorized(String path) {
    return HttpRequest.newBuilder(URI.create(properties.managementApi() + path))
        .header("Authorization", "Bearer " + token())
        .header("Accept", "application/json")
        .timeout(TIMEOUT);
  }

  /**
   * The shared token, or a new one when it is missing or about to expire. No lock is held across
   * the call to Auth0: instances refreshing at once each spend a token, which costs less than a
   * database connection held while Auth0 answers.
   */
  private String token() {
    var current = tokens.current().filter(HttpAuth0Users::fresh);
    if (current.isPresent()) {
      return current.get().value();
    }
    var fetched = requestToken();
    try {
      tokens.save(fetched);
    } catch (DataIntegrityViolationException _) {
      // Another instance stored the first token at the same moment; this one is valid too.
    }
    return fetched.value();
  }

  // A minute of slack, so a token never expires between this check and Auth0 reading it.
  private static boolean fresh(Auth0Tokens.Token token) {
    return token.expiresAt().isAfter(Instant.now().plusSeconds(60));
  }

  private Auth0Tokens.Token requestToken() {
    var body =
        json.writeValueAsString(
            Map.of(
                "grant_type", "client_credentials",
                "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(),
                "audience", properties.managementApi()));
    var request =
        // From the tenant's own domain: the Management API refuses a token the custom domain
        // issued, as having the wrong issuer.
        HttpRequest.newBuilder(URI.create(properties.managementApi()).resolve("/oauth/token"))
            .header("Content-Type", "application/json")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var answer = read(send(request));
    return new Auth0Tokens.Token(
        answer.path("access_token").asString(),
        Instant.now().plusSeconds(answer.path("expires_in").asLong(60)));
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, BodyHandlers.ofString());
    } catch (IOException e) {
      throw unavailable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(e);
    }
  }

  private JsonNode read(HttpResponse<String> response) {
    if (response.statusCode() / 100 != 2) {
      throw unavailable(null);
    }
    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static ResponseStatusException unavailable(Exception cause) {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Auth0 did not answer", cause);
  }
}

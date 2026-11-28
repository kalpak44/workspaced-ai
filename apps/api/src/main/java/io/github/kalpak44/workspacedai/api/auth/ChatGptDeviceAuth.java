package io.github.kalpak44.workspacedai.api.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The device-code protocol used by pinned OpenCode 2.2.5's chatgpt-headless method. */
@Component
public class ChatGptDeviceAuth {
  private static final String CLIENT = "app_EMoamEEZ73f0CkXaXp7hrann";
  private final URI issuer;
  private final JsonMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public ChatGptDeviceAuth(JsonMapper json) {
    this(json, URI.create("https://auth.openai.com"));
  }

  ChatGptDeviceAuth(JsonMapper json, URI issuer) {
    this.json = json;
    this.issuer = issuer;
  }

  JsonNode start() {
    return parse(
        post(
                "/api/accounts/deviceauth/usercode",
                json.writeValueAsString(Map.of("client_id", CLIENT)),
                false)
            .body());
  }

  /** null means authorization is still pending; no provider error bodies escape this class. */
  String poll(String state) {
    var response = post("/api/accounts/deviceauth/token", state, false);
    if (response.statusCode() == 403 || response.statusCode() == 404) return null;
    var code = parse(response.body());
    var form =
        Map.of(
            "grant_type",
            "authorization_code",
            "code",
            required(code, "authorization_code"),
            "code_verifier",
            required(code, "code_verifier"),
            "client_id",
            CLIENT,
            "redirect_uri",
            "https://auth.openai.com/deviceauth/callback");
    var encoded =
        form.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    var tokens = parse(post("/oauth/token", encoded, true).body());
    var access = required(tokens, "access_token");
    var metadata = new HashMap<String, String>();
    for (var field : List.of("id_token", "access_token")) {
      try {
        var claims =
            json.readTree(
                Base64.getUrlDecoder().decode(tokens.path(field).asString().split("\\.")[1]));
        var account =
            claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asString("");
        if (account.isBlank()) account = claims.path("chatgpt_account_id").asString("");
        if (!account.isBlank()) {
          metadata.put("accountID", account);
          break;
        }
      } catch (RuntimeException ignored) {
        /* The token itself remains opaque to the connector. */
      }
    }
    return json.writeValueAsString(
        Map.of(
            "type",
            "oauth",
            "methodID",
            "chatgpt-headless",
            "access",
            access,
            "refresh",
            required(tokens, "refresh_token"),
            "expires",
            Instant.now().plusSeconds(tokens.path("expires_in").asLong(3600)).toEpochMilli(),
            "metadata",
            metadata));
  }

  static String required(JsonNode node, String field) {
    var value = node.path(field).asString("");
    if (value.isBlank()) throw unavailable();
    return value;
  }

  private JsonNode parse(String body) {
    try {
      return json.readTree(body);
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private HttpResponse<String> post(String path, String body, boolean form) {
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(issuer.resolve(path))
                  .timeout(Duration.ofSeconds(20))
                  .header(
                      "Content-Type",
                      form ? "application/x-www-form-urlencoded" : "application/json")
                  .header("User-Agent", "opencode/2.2.5")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (path.endsWith("/token")
          && !form
          && (response.statusCode() == 403 || response.statusCode() == 404)) return response;
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
      return response;
    } catch (java.io.IOException e) {
      throw unavailable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "ChatGPT sign-in is unavailable. Please try again.");
  }
}

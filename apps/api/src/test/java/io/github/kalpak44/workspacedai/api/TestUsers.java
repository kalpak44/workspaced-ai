package io.github.kalpak44.workspacedai.api;

import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/** The default admin/admin account, and a signed-in token for it on each test server. */
public final class TestUsers {

  public static final UUID ADMIN = UserEntity.DEFAULT_ADMIN_ID;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<Integer, String> TOKENS = new ConcurrentHashMap<>();

  private TestUsers() {}

  /** The Authorization header value for admin on the server listening on that port. */
  public static String bearer(int port) {
    return "Bearer " + TOKENS.computeIfAbsent(port, p -> login(p, "admin", "admin"));
  }

  public static String login(int port, String username, String password) {
    var client = HttpClient.newHttpClient();
    try {
      var issued =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf"))
                  .GET()
                  .build(),
              BodyHandlers.ofString());
      var csrf =
          issued.headers().allValues("Set-Cookie").stream()
              .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
              .map(cookie -> cookie.substring(0, cookie.indexOf(';')))
              .findFirst()
              .orElseThrow();
      var token = JSON.readTree(issued.body()).get("token").asString();
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
              .header("Content-Type", "application/json")
              .header("Cookie", csrf)
              .header("X-XSRF-TOKEN", token)
              .POST(
                  BodyPublishers.ofString(
                      JSON.writeValueAsString(Map.of("username", username, "password", password))))
              .build();
      var response = client.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("login answered " + response.statusCode());
      }
      return JSON.readTree(response.body()).get("token").asString();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

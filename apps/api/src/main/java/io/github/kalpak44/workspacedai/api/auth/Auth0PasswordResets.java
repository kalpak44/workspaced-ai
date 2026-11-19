package io.github.kalpak44.workspacedai.api.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auth0's change-password endpoint, which sends the mail itself. It needs only the client id, so
 * the API holds no Management API credential for this.
 */
@Component
public class Auth0PasswordResets implements PasswordResets {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final AuthProperties properties;
  private final JsonMapper json;
  private final HttpClient http;

  public Auth0PasswordResets(AuthProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @Override
  public void send(String email) {
    var body =
        Map.of(
            "client_id", properties.clientId(),
            "email", email,
            "connection", properties.connection());
    var request =
        HttpRequest.newBuilder(URI.create(properties.issuer() + "dbconnections/change_password"))
            .header("Content-Type", "application/json")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    int status;
    try {
      status = http.send(request, BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      throw unavailable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(e);
    }
    if (status != 200) {
      throw unavailable(null);
    }
  }

  private static ResponseStatusException unavailable(Exception cause) {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "the password reset mail could not be sent", cause);
  }
}

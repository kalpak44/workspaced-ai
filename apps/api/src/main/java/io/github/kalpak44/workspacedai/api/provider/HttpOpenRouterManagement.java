package io.github.kalpak44.workspacedai.api.provider;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpOpenRouterManagement implements OpenRouterManagement {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private final String managementKey;
  private final URI keysUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @org.springframework.beans.factory.annotation.Autowired
  public HttpOpenRouterManagement(
      @Value("${workspaced.openrouter.management-key:}") String managementKey) {
    this(managementKey, URI.create("https://openrouter.ai/api/v1/keys"));
  }

  HttpOpenRouterManagement(String managementKey, URI keysUrl) {
    this.managementKey = managementKey == null ? "" : managementKey.strip();
    this.keysUrl = keysUrl;
  }

  @Override
  public boolean configured() {
    return !managementKey.isBlank();
  }

  @Override
  public IssuedKey createTrial(UUID userId) {
    requireConfigured();
    var body =
        json.createObjectNode()
            .put("name", "workspaced trial: " + userId)
            .put("limit", BigDecimal.ONE)
            .putNull("limit_reset")
            .put("include_byok_in_limit", true);
    var request =
        request(keysUrl)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    try {
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 201 && response.statusCode() != 200) throw unavailable();
      var data = json.readTree(response.body());
      var key = data.path("key").asString("");
      var hash = data.path("data").path("hash").asString("");
      var limit = data.path("data").path("limit");
      if (key.isBlank()
          || !hash.matches("[A-Za-z0-9_-]{1,200}")
          || !limit.isNumber()
          || limit.decimalValue().compareTo(BigDecimal.ONE) != 0
          || !data.path("data").path("limit_reset").isNull()) throw unavailable();
      return new IssuedKey(key, hash);
    } catch (IOException | JacksonException | IllegalArgumentException e) {
      throw unavailable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  @Override
  public void revoke(String hash) {
    requireConfigured();
    if (hash == null || !hash.matches("[A-Za-z0-9_-]{1,200}")) throw unavailable();
    try {
      var response =
          http.send(
              request(URI.create(keysUrl + "/" + hash)).DELETE().build(),
              BodyHandlers.discarding());
      if (response.statusCode() != 200
          && response.statusCode() != 204
          && response.statusCode() != 404) throw unavailable();
    } catch (IOException e) {
      throw unavailable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + managementKey)
        .header("Accept", "application/json");
  }

  private void requireConfigured() {
    if (!configured()) throw unavailable();
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The trial AI connector is temporarily unavailable. Please try again later.");
  }
}

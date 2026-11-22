package io.github.kalpak44.workspacedai.api.provider;

import io.github.kalpak44.workspacedai.api.workspace.ApiKeyRejectedException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpOpenRouterMetrics implements OpenRouterMetrics {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @Override
  public OpenRouterKeyMetrics read(String key) {
    return read(URI.create("https://openrouter.ai/api/v1/key"), key);
  }

  // The URL is supplied only by local HTTP tests; callers cannot redirect a saved key.
  OpenRouterKeyMetrics read(URI url, String key) {
    try {
      var request =
          HttpRequest.newBuilder(url)
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + key)
              .GET()
              .build();
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() == 401 || response.statusCode() == 403)
        throw new ApiKeyRejectedException(ModelProvider.OPENROUTER);
      if (response.statusCode() == 429)
        throw new ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "OpenRouter is rate limiting usage checks. Try again shortly.");
      if (response.statusCode() / 100 != 2) throw new IOException("provider usage check failed");
      var data = json.readTree(response.body()).path("data");
      if (!data.isObject()) throw new IOException("missing metrics");
      var free = data.path("free_model_daily_requests");
      return new OpenRouterKeyMetrics(
          amount(data, "usage"),
          amount(data, "usage_daily"),
          amount(data, "usage_weekly"),
          amount(data, "usage_monthly"),
          amount(data, "limit"),
          amount(data, "limit_remaining"),
          text(data, "limit_reset"),
          text(data, "expires_at"),
          flag(data, "include_byok_in_limit"),
          amount(data, "byok_usage"),
          flag(data, "is_free_tier"),
          count(free, "limit"),
          count(free, "remaining"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderUnreachableException(ModelProvider.OPENROUTER, null);
    } catch (IOException | IllegalArgumentException | JacksonException e) {
      // Never propagate the provider response or request: either may contain credentials.
      throw new ProviderUnreachableException(ModelProvider.OPENROUTER, null);
    }
  }

  private static BigDecimal amount(JsonNode data, String name) {
    var value = data.path(name);
    return value.isNumber() ? value.decimalValue() : null;
  }

  private static Long count(JsonNode data, String name) {
    var value = data.path(name);
    return value.isIntegralNumber() ? value.longValue() : null;
  }

  private static String text(JsonNode data, String name) {
    var value = data.path(name);
    return value.isString() ? value.asString() : null;
  }

  private static Boolean flag(JsonNode data, String name) {
    var value = data.path(name);
    return value.isBoolean() ? value.booleanValue() : null;
  }
}

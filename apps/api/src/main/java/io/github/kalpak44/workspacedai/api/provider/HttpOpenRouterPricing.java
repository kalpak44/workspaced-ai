package io.github.kalpak44.workspacedai.api.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads OpenRouter's public model list, which needs no key. Prices change rarely and every chat
 * turn asks for them, so a list is reused for an hour; a failed read is retried on the next ask.
 */
@Component
public class HttpOpenRouterPricing implements OpenRouterPricing {
  private static final Logger log = LoggerFactory.getLogger(HttpOpenRouterPricing.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration FRESH_FOR = Duration.ofHours(1);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final URI url;
  private final Clock clock;
  private Map<String, Price> cached = Map.of();
  private Instant readAt = Instant.MIN;

  public HttpOpenRouterPricing() {
    this(URI.create(ModelProvider.OPENROUTER.baseUrl() + "/models"), Clock.systemUTC());
  }

  // The URL and clock are supplied only by local HTTP tests.
  HttpOpenRouterPricing(URI url, Clock clock) {
    this.url = url;
    this.clock = clock;
  }

  @Override
  public synchronized Map<String, Price> prices() {
    var now = clock.instant();
    if (!cached.isEmpty() && readAt.plus(FRESH_FOR).isAfter(now)) {
      return cached;
    }
    try {
      cached = read();
      readAt = now;
    } catch (IOException | IllegalArgumentException | JacksonException e) {
      log.warn("Could not read OpenRouter's model prices: {}", e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return cached;
  }

  private Map<String, Price> read() throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
    var response = http.send(request, BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("OpenRouter answered " + response.statusCode());
    }
    var prices = new HashMap<String, Price>();
    for (var model : json.readTree(response.body()).path("data")) {
      var pricing = model.path("pricing");
      var input = perMillion(pricing, "prompt");
      var output = perMillion(pricing, "completion");
      if (model.path("id").isString() && input != null && output != null) {
        prices.put(model.path("id").asString(), new Price(input, output));
      }
    }
    return Map.copyOf(prices);
  }

  /** OpenRouter quotes USD per token as a decimal string; the catalogue speaks per million. */
  private static Double perMillion(JsonNode pricing, String name) {
    var value = pricing.path(name);
    if (!value.isString()) {
      return null;
    }
    try {
      return Double.parseDouble(value.asString()) * 1_000_000;
    } catch (NumberFormatException _) {
      return null;
    }
  }
}

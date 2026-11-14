package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Hetzner Cloud API, called with the project token. */
@Component
public class HetznerClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HetznerProperties properties;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  public HetznerClient(HetznerProperties properties) {
    this.properties = properties;
  }

  public String location() {
    return properties.location();
  }

  /** Every server type the project can see, across all of Hetzner's pages. */
  public List<ServerType> serverTypes() {
    var types = new ArrayList<ServerType>();
    var page = 1;
    while (page > 0) {
      var body = get("/server_types?per_page=50&page=" + page);
      body.path("server_types").forEach(type -> types.add(serverType(type)));
      var next = body.path("meta").path("pagination").path("next_page");
      page = next.isNumber() ? next.asInt() : 0;
    }
    return types;
  }

  private JsonNode get(String path) {
    if (!properties.hasToken()) {
      throw new HetznerException.NotConfigured();
    }
    var request =
        HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json")
            .timeout(TIMEOUT)
            .GET()
            .build();
    try {
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new HetznerException("Hetzner answered " + response.statusCode() + " on " + path);
      }
      return JSON.readTree(response.body());
    } catch (IOException | JacksonException e) {
      throw new HetznerException("could not read " + path + " from Hetzner", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HetznerException("interrupted while asking Hetzner for " + path, e);
    }
  }

  private static ServerType serverType(JsonNode type) {
    var prices = new ArrayList<ServerType.Price>();
    type.path("prices")
        .forEach(
            price ->
                prices.add(
                    new ServerType.Price(
                        price.path("location").asString(),
                        amount(price.path("price_monthly").path("net")),
                        amount(price.path("price_monthly").path("gross")))));
    return new ServerType(
        type.path("name").asString(),
        type.path("cores").asInt(),
        type.path("memory").asDouble(),
        type.path("disk").asInt(),
        type.path("cpu_type").asString(),
        type.path("architecture").asString(),
        !type.path("deprecation").isNull() && !type.path("deprecation").isMissingNode(),
        List.copyOf(prices));
  }

  // Hetzner sends prices as strings with ten decimals; cents are what anyone reads.
  private static BigDecimal amount(JsonNode value) {
    return new BigDecimal(value.asString()).setScale(2, RoundingMode.HALF_UP);
  }
}

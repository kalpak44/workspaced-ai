package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls the Hetzner Cloud API over HTTP with the project token. */
@Component
public class HttpHetznerClient implements HetznerClient {

  private final HetznerProperties properties;
  private final HttpClient http;
  private final JsonMapper json;

  public HttpHetznerClient(HetznerProperties properties, HttpClient http, JsonMapper json) {
    this.properties = properties;
    this.http = http;
    this.json = json;
  }

  /** Follows Hetzner's pagination, so a type on a later page is never missed. */
  @Override
  public List<ServerType> serverTypes() {
    var types = new ArrayList<ServerType>();
    var page = 1;
    while (page > 0) {
      var body = get("/server_types?per_page=50&page=" + page);
      body.path("server_types").forEach(type -> types.add(serverType(type)));
      page = nextPage(body);
    }
    return List.copyOf(types);
  }

  @Override
  public List<Server> servers(String labelSelector) {
    var servers = new ArrayList<Server>();
    var selector = URLEncoder.encode(labelSelector, StandardCharsets.UTF_8);
    var page = 1;
    while (page > 0) {
      var body = get("/servers?per_page=50&label_selector=" + selector + "&page=" + page);
      body.path("servers").forEach(server -> servers.add(server(server)));
      page = nextPage(body);
    }
    return List.copyOf(servers);
  }

  /**
   * Public IPv4 and IPv6 both on, so the VM can reach the API with no network of ours, and behind
   * the firewall from its first boot, so nothing can reach the VM. The answer also carries a root
   * password when no SSH key is given; it is deliberately never read.
   */
  @Override
  public CreatedServer createServer(ServerSpec spec) {
    var firewall = firewallId();
    var body = json.createObjectNode();
    body.put("name", spec.name());
    body.put("server_type", spec.serverType());
    body.put("location", spec.location());
    body.put("image", spec.image());
    body.put("start_after_create", true);
    var keys = body.putArray("ssh_keys");
    spec.sshKeys().forEach(keys::add);
    var labels = body.putObject("labels");
    spec.labels().forEach(labels::put);
    body.putArray("firewalls").addObject().put("firewall", firewall);
    var response =
        send(
            "/servers",
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
            "POST",
            201);
    var server = response.path("server");
    return new CreatedServer(server.path("id").asLong(), ipv4(server));
  }

  @Override
  public void deleteServer(long id) {
    try {
      send("/servers/" + id, HttpRequest.BodyPublishers.noBody(), "DELETE", 200);
    } catch (Gone _) {
      // Already deleted, by an earlier attempt or by hand.
    }
  }

  /**
   * Looked up by name on every create rather than cached, so a firewall deleted by hand is made
   * again instead of failing every create after it. Made with no rules: a Hetzner firewall drops
   * every inbound packet no rule allows, and lets every outbound one through.
   */
  private long firewallId() {
    var name = URLEncoder.encode(properties.firewall(), StandardCharsets.UTF_8);
    var found = get("/firewalls?name=" + name).path("firewalls");
    if (!found.isEmpty()) {
      return found.get(0).path("id").asLong();
    }
    var body = json.createObjectNode();
    body.put("name", properties.firewall());
    body.putArray("rules");
    var created =
        send(
            "/firewalls",
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
            "POST",
            201);
    return created.path("firewall").path("id").asLong();
  }

  private JsonNode get(String path) {
    return send(path, HttpRequest.BodyPublishers.noBody(), "GET", 200);
  }

  private JsonNode send(
      String path, HttpRequest.BodyPublisher body, String method, int expectedStatus) {
    if (!properties.hasToken()) {
      throw new HetznerException.NotConfigured();
    }
    var request =
        HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
            .header("Authorization", "Bearer " + properties.token())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(properties.timeout())
            .method(method, body)
            .build();
    try {
      var response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        throw new Gone(method + " " + path);
      }
      if (response.statusCode() != expectedStatus) {
        throw new HetznerException(
            "Hetzner answered " + response.statusCode() + " on " + method + " " + path);
      }
      // A 204 has no body to read.
      return response.body().isBlank() ? json.missingNode() : json.readTree(response.body());
    } catch (IOException | JacksonException e) {
      throw new HetznerException("could not read " + method + " " + path + " from Hetzner", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HetznerException("interrupted on " + method + " " + path + " at Hetzner", e);
    }
  }

  /** A 404: only a delete treats it as success, everywhere else it is a failure like any other. */
  private static final class Gone extends HetznerException {
    Gone(String request) {
      super("Hetzner answered 404 on " + request);
    }
  }

  // Zero once there is no next page.
  private static int nextPage(JsonNode body) {
    var next = body.path("meta").path("pagination").path("next_page");
    return next.isNumber() ? next.asInt() : 0;
  }

  private static String ipv4(JsonNode server) {
    var ip = server.path("public_net").path("ipv4").path("ip");
    return ip.isString() ? ip.asString() : null;
  }

  private static Map<String, String> labels(JsonNode resource) {
    var labels = new HashMap<String, String>();
    resource
        .path("labels")
        .properties()
        .forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
    return Map.copyOf(labels);
  }

  private static Server server(JsonNode server) {
    return new Server(
        server.path("id").asLong(),
        server.path("name").asString(),
        server.path("server_type").path("name").asString(),
        ipv4(server),
        Instant.parse(server.path("created").asString()),
        labels(server));
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
    var deprecation = type.path("deprecation");
    return new ServerType(
        type.path("name").asString(),
        type.path("cores").asInt(),
        type.path("memory").asDouble(),
        type.path("disk").asInt(),
        type.path("cpu_type").asString(),
        type.path("architecture").asString(),
        !deprecation.isNull() && !deprecation.isMissingNode(),
        List.copyOf(prices));
  }

  // Hetzner sends prices as strings with ten decimals; cents are what anyone reads.
  private static BigDecimal amount(JsonNode value) {
    return new BigDecimal(value.asString()).setScale(2, RoundingMode.HALF_UP);
  }
}

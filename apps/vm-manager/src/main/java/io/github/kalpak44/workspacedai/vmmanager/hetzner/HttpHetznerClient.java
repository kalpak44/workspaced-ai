package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Calls the Hetzner Cloud API over HTTP with the project token. */
@Component
public class HttpHetznerClient implements HetznerClient {

  private static final String LABELS = "labels";
  private static final String RULES = "rules";

  // Hetzner drops outbound traffic no rule allows once a firewall has one, so these allow all of
  // it but direct SMTP (25, 465): a VM sending spam gets the whole project suspended. Submission
  // on 587 stays open for apps that send through a mail provider. Users never see or set these.
  private static final List<List<String>> OUTBOUND =
      List.of(
          List.of("tcp", "1-24"),
          List.of("tcp", "26-464"),
          List.of("tcp", "466-65535"),
          List.of("udp", "1-65535"),
          List.of("icmp"),
          List.of("esp"),
          List.of("gre"));

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
  public List<ServerType.Price> primaryIpv4Prices() {
    var prices = new ArrayList<ServerType.Price>();
    get("/pricing")
        .path("pricing")
        .path("primary_ips")
        .forEach(
            ip -> {
              if ("ipv4".equals(ip.path("type").asString())) {
                ip.path("prices").forEach(price -> prices.add(price(price)));
              }
            });
    return List.copyOf(prices);
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
   * its firewall from its first boot, so nothing reaches the VM that its rules do not let in. The
   * answer also carries a root password when no SSH key is given; it is deliberately never read.
   */
  @Override
  public CreatedServer createServer(ServerSpec spec) {
    var image = properties.hasImageSelector() ? newestSnapshot() : spec.image();
    var body = json.createObjectNode();
    body.put("name", spec.name());
    body.put("server_type", spec.serverType());
    body.put("location", spec.location());
    body.put("image", image);
    body.put("start_after_create", true);
    var keys = body.putArray("ssh_keys");
    spec.sshKeys().forEach(keys::add);
    var labels = body.putObject(LABELS);
    spec.labels().forEach(labels::put);
    body.putArray("firewalls").addObject().put("firewall", spec.firewall());
    if (spec.userData() != null) {
      body.put("user_data", spec.userData());
    }
    var response =
        send(
            "/servers",
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
            "POST",
            201);
    var server = response.path("server");
    return new CreatedServer(server.path("id").asLong(), ipv4(server), ipv6(server));
  }

  @Override
  public void deleteServer(long id) {
    try {
      send("/servers/" + id, HttpRequest.BodyPublishers.noBody(), "DELETE", 200);
    } catch (Gone _) {
      // Already deleted, by an earlier attempt or by hand.
    }
  }

  @Override
  public void shutdownServer(long id) {
    send("/servers/" + id + "/actions/shutdown", HttpRequest.BodyPublishers.noBody(), "POST", 201);
  }

  @Override
  public void powerOnServer(long id) {
    send("/servers/" + id + "/actions/poweron", HttpRequest.BodyPublishers.noBody(), "POST", 201);
  }

  @Override
  public long createFirewall(String name, Map<String, String> labels) {
    var body = json.createObjectNode();
    body.put("name", name);
    var labelNode = body.putObject(LABELS);
    labels.forEach(labelNode::put);
    outbound(body.putArray(RULES));
    var created =
        send(
            "/firewalls",
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
            "POST",
            201);
    return created.path("firewall").path("id").asLong();
  }

  @Override
  public List<Firewall> firewalls(String labelSelector) {
    var firewalls = new ArrayList<Firewall>();
    var selector = URLEncoder.encode(labelSelector, StandardCharsets.UTF_8);
    var page = 1;
    while (page > 0) {
      var body = get("/firewalls?per_page=50&label_selector=" + selector + "&page=" + page);
      body.path("firewalls").forEach(firewall -> firewalls.add(firewall(firewall)));
      page = nextPage(body);
    }
    return List.copyOf(firewalls);
  }

  @Override
  public void setFirewallRules(long id, List<FirewallRule> rules) {
    var body = json.createObjectNode();
    var array = body.putArray(RULES);
    for (var rule : rules) {
      var node = array.addObject();
      node.put("direction", "in");
      node.put("protocol", rule.protocol());
      if (rule.port() != null) {
        node.put("port", rule.port());
      }
      var sources = node.putArray("source_ips");
      rule.sourceIps().forEach(sources::add);
      if (rule.description() != null) {
        node.put("description", rule.description());
      }
    }
    outbound(array);
    send(
        "/firewalls/" + id + "/actions/set_rules",
        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)),
        "POST",
        201);
  }

  private static void outbound(ArrayNode rules) {
    for (var allowed : OUTBOUND) {
      var node = rules.addObject();
      node.put("direction", "out");
      node.put("protocol", allowed.getFirst());
      if (allowed.size() > 1) {
        node.put("port", allowed.get(1));
      }
      var destinations = node.putArray("destination_ips");
      destinations.add("0.0.0.0/0");
      destinations.add("::/0");
    }
  }

  @Override
  public void deleteFirewall(long id) {
    try {
      send("/firewalls/" + id, HttpRequest.BodyPublishers.noBody(), "DELETE", 204);
    } catch (Gone _) {
      // Already deleted, by an earlier attempt or by hand.
    }
  }

  @Override
  public ServerMetrics metrics(long id, Instant start, Instant end, Duration step) {
    var query =
        "type=cpu,disk,network&start="
            + URLEncoder.encode(start.toString(), StandardCharsets.UTF_8)
            + "&end="
            + URLEncoder.encode(end.toString(), StandardCharsets.UTF_8)
            + "&step="
            + step.toSeconds();
    var series = get("/servers/" + id + "/metrics?" + query).path("metrics").path("time_series");
    var stamps = new TreeSet<Instant>();
    var points = new LinkedHashMap<String, Map<Instant, Double>>();
    series
        .properties()
        .forEach(
            entry -> {
              var values = new HashMap<Instant, Double>();
              entry
                  .getValue()
                  .path("values")
                  .forEach(
                      point -> {
                        var at = Instant.ofEpochSecond(Math.round(point.path(0).asDouble()));
                        stamps.add(at);
                        values.put(at, sample(point.path(1)));
                      });
              points.put(entry.getKey(), values);
            });
    var timestamps = List.copyOf(stamps);
    var aligned = new LinkedHashMap<String, List<Double>>();
    points.forEach(
        (name, values) -> aligned.put(name, timestamps.stream().map(values::get).toList()));
    return new ServerMetrics(timestamps, Collections.unmodifiableMap(aligned));
  }

  // Hetzner sends each value as a string, and "NaN" where it has no sample.
  private static Double sample(JsonNode value) {
    try {
      var parsed = Double.parseDouble(value.asString());
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException _) {
      return null;
    }
  }

  /**
   * Looked up on every create, so a VM always starts from the latest build. None at all fails the
   * create rather than falling back to a plain image the rest of the system does not expect.
   */
  private String newestSnapshot() {
    var selector = URLEncoder.encode(properties.imageSelector(), StandardCharsets.UTF_8);
    var images =
        get("/images?type=snapshot&sort=created:desc&per_page=1&label_selector=" + selector)
            .path("images");
    if (images.isEmpty()) {
      throw new HetznerException("no snapshot is labelled " + properties.imageSelector());
    }
    return images.get(0).path("id").asString();
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
            "Hetzner answered " + response.statusCode() + " on " + method + " " + path,
            response.statusCode());
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

  /**
   * Hetzner assigns each server a /64 and configures the first address in it, ::1, on the server;
   * the network alone is not an address anything can point at.
   */
  static String ipv6(JsonNode server) {
    var ip = server.path("public_net").path("ipv6").path("ip");
    if (!ip.isString()) {
      return null;
    }
    var network = ip.asString().split("/", 2)[0];
    try {
      var bytes = InetAddress.ofLiteral(network).getAddress();
      bytes[bytes.length - 1] |= 1;
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (IllegalArgumentException | UnknownHostException _) {
      return null;
    }
  }

  private static Map<String, String> labels(JsonNode resource) {
    var labels = new HashMap<String, String>();
    resource
        .path(LABELS)
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
        ipv6(server),
        Instant.parse(server.path("created").asString()),
        labels(server),
        server.path("status").asString());
  }

  // Outbound rules are the fixed ones, so only inbound ones are read back.
  private static Firewall firewall(JsonNode firewall) {
    var rules = new ArrayList<FirewallRule>();
    firewall
        .path(RULES)
        .forEach(
            rule -> {
              if (!"in".equals(rule.path("direction").asString())) {
                return;
              }
              var sources = new ArrayList<String>();
              rule.path("source_ips").forEach(ip -> sources.add(ip.asString()));
              var port = rule.path("port");
              var description = rule.path("description");
              rules.add(
                  new FirewallRule(
                      rule.path("protocol").asString(),
                      port.isString() ? port.asString() : null,
                      List.copyOf(sources),
                      description.isString() ? description.asString() : null));
            });
    return new Firewall(
        firewall.path("id").asLong(),
        firewall.path("name").asString(),
        Instant.parse(firewall.path("created").asString()),
        labels(firewall),
        List.copyOf(rules),
        firewall.path("applied_to").size());
  }

  private static ServerType serverType(JsonNode type) {
    var prices = new ArrayList<ServerType.Price>();
    type.path("prices").forEach(price -> prices.add(price(price)));
    // An older answer has no flag, and sold out is only what Hetzner says outright.
    var soldOutIn = new ArrayList<String>();
    type.path("locations")
        .forEach(
            location -> {
              if (!location.path("available").asBoolean(true)) {
                soldOutIn.add(location.path("name").asString());
              }
            });
    var deprecation = type.path("deprecation");
    return new ServerType(
        type.path("name").asString(),
        type.path("cores").asInt(),
        type.path("memory").asDouble(),
        type.path("disk").asInt(),
        type.path("cpu_type").asString(),
        type.path("architecture").asString(),
        !deprecation.isNull() && !deprecation.isMissingNode(),
        List.copyOf(prices),
        List.copyOf(soldOutIn));
  }

  private static ServerType.Price price(JsonNode price) {
    return new ServerType.Price(
        price.path("location").asString(),
        amount(price.path("price_monthly").path("net")),
        amount(price.path("price_monthly").path("gross")));
  }

  // Hetzner sends prices as strings with ten decimals; cents are what anyone reads.
  private static BigDecimal amount(JsonNode value) {
    return new BigDecimal(value.asString()).setScale(2, RoundingMode.HALF_UP);
  }
}

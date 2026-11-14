package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers as the Hetzner Cloud API does, so no test calls the real one: /server_types in two pages
 * (a trimmed copy of a real answer), and /servers listed by label, created and deleted in memory.
 * Setting {@link #status} makes every route answer with it instead.
 */
public class FakeHetzner implements AutoCloseable {

  static final String PAGE_1 =
      """
      {"server_types":[
        {"name":"cx23","cores":2,"memory":4.0,"disk":40,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"nbg1","price_monthly":{"net":"5.4900000000","gross":"6.5880000000000000"}},
                   {"location":"hel1","price_monthly":{"net":"5.4900000000","gross":"6.5880000000000000"}}]},
        {"name":"cx22","cores":2,"memory":4.0,"disk":40,"cpu_type":"shared",
         "architecture":"x86","deprecation":{"announced":"2025-10-01T00:00:00Z"},
         "prices":[{"location":"nbg1","price_monthly":{"net":"3.7900000000","gross":"4.5101000000000000"}}]}
      ],"meta":{"pagination":{"page":1,"next_page":2}}}
      """;

  static final String PAGE_2 =
      """
      {"server_types":[
        {"name":"cx33","cores":4,"memory":8.0,"disk":80,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"nbg1","price_monthly":{"net":"8.4900000000","gross":"10.1880000000000000"}}]},
        {"name":"cx43","cores":8,"memory":16.0,"disk":160,"cpu_type":"shared",
         "architecture":"x86","deprecation":null,
         "prices":[{"location":"hel1","price_monthly":{"net":"15.9900000000","gross":"19.1880000000000000"}}]}
      ],"meta":{"pagination":{"page":2,"next_page":null}}}
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  public final List<String> authorizations = new CopyOnWriteArrayList<>();
  public final AtomicInteger requests = new AtomicInteger();
  public final List<JsonNode> createBodies = new CopyOnWriteArrayList<>();
  public final Map<Long, ObjectNode> servers = new ConcurrentHashMap<>();
  public volatile int status = 200;
  private final AtomicLong ids = new AtomicLong(5000);

  public FakeHetzner() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/server_types",
        exchange -> {
          record(exchange);
          var page = exchange.getRequestURI().getQuery().contains("page=2") ? PAGE_2 : PAGE_1;
          answer(exchange, 200, page);
        });
    server.createContext("/servers", this::servers);
    server.start();
  }

  private void servers(HttpExchange exchange) throws IOException {
    record(exchange);
    var path = exchange.getRequestURI().getPath();
    switch (exchange.getRequestMethod()) {
      case "GET" -> {
        var list = JSON.createArrayNode();
        servers.values().stream().filter(s -> matches(exchange, s)).forEach(list::add);
        var body = JSON.createObjectNode();
        body.set("servers", list);
        body.putObject("meta").putObject("pagination").putNull("next_page");
        answer(exchange, 200, body.toString());
      }
      case "POST" -> {
        var request = JSON.readTree(exchange.getRequestBody());
        createBodies.add(request);
        var id = ids.incrementAndGet();
        var created = JSON.createObjectNode();
        created.put("id", id);
        created.put("name", request.path("name").asString());
        created.put("created", "2026-11-14T10:00:00Z");
        created.putObject("server_type").put("name", request.path("server_type").asString());
        created.putObject("public_net").putObject("ipv4").put("ip", "203.0.113." + (id % 250));
        created.set("labels", request.path("labels"));
        servers.put(id, created);
        var body = JSON.createObjectNode();
        body.set("server", created);
        body.put("root_password", "never-read");
        answer(exchange, 201, body.toString());
      }
      case "DELETE" -> {
        var id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        answer(exchange, servers.remove(id) == null ? 404 : 200, "{\"action\":{}}");
      }
      default -> answer(exchange, 405, "{}");
    }
  }

  // The label_selector query parameter: comma-joined key=value pairs, all of which must match.
  private static boolean matches(HttpExchange exchange, JsonNode resource) {
    var query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
    var selector = query.replaceAll(".*label_selector=([^&]*).*", "$1");
    for (var term : selector.split(",")) {
      var kv = term.split("=", 2);
      if (!kv[1].equals(resource.path("labels").path(kv[0]).asString())) {
        return false;
      }
    }
    return true;
  }

  private void record(HttpExchange exchange) {
    requests.incrementAndGet();
    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
  }

  private void answer(HttpExchange exchange, int code, String body) throws IOException {
    var effective = status == 200 ? code : status;
    var bytes = (status == 200 ? body : "{\"error\":{}}").getBytes(StandardCharsets.UTF_8);
    // HttpServer takes -1 as "no body", which is what a 204 must be.
    exchange.sendResponseHeaders(effective, bytes.length == 0 ? -1 : bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  public URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

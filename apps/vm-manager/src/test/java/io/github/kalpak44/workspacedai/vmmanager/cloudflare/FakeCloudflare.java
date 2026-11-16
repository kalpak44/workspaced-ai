package io.github.kalpak44.workspacedai.vmmanager.cloudflare;

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
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers as Cloudflare's DNS records API does for zone {@code z1}: listed by name suffix one page
 * per {@link #perPage} records, created, patched and deleted in memory. Setting {@link #status}
 * makes every route answer with it and an error instead.
 */
public class FakeCloudflare implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  public final Map<String, ObjectNode> records = new ConcurrentSkipListMap<>();
  public final List<String> authorizations = new CopyOnWriteArrayList<>();
  public final List<String> queries = new CopyOnWriteArrayList<>();
  public final AtomicInteger requests = new AtomicInteger();
  public volatile int perPage = 100;
  public volatile int status = 200;
  private final AtomicLong ids = new AtomicLong();

  public FakeCloudflare() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/zones/z1/dns_records", this::records);
    server.start();
  }

  public String add(String type, String name, String content) {
    var id = "r" + ids.incrementAndGet();
    records.put(
        id,
        JSON.createObjectNode()
            .put("id", id)
            .put("type", type)
            .put("name", name)
            .put("content", content)
            .put("ttl", 1)
            .put("proxied", false));
    return id;
  }

  private void records(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
    var path = exchange.getRequestURI().getPath();
    var id = path.substring(path.lastIndexOf('/') + 1);
    switch (exchange.getRequestMethod()) {
      case "GET" -> list(exchange);
      case "POST" -> {
        var request = (ObjectNode) JSON.readTree(exchange.getRequestBody());
        var created = "r" + ids.incrementAndGet();
        records.put(created, request.put("id", created));
        answer(exchange, 200, JSON.createObjectNode().set("result", request));
      }
      case "PATCH" -> {
        var record = records.get(id);
        if (record == null) {
          answer(exchange, 404, null);
          return;
        }
        record.setAll((ObjectNode) JSON.readTree(exchange.getRequestBody()));
        answer(exchange, 200, JSON.createObjectNode().set("result", record));
      }
      case "DELETE" -> {
        var gone = records.remove(id) == null;
        answer(exchange, gone ? 404 : 200, JSON.createObjectNode().put("result", id));
      }
      default -> answer(exchange, 405, null);
    }
  }

  private void list(HttpExchange exchange) throws IOException {
    var query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
    queries.add(query);
    var suffix = query.replaceAll(".*name\\.endswith=([^&]*).*", "$1");
    var page = Integer.parseInt(query.replaceAll(".*[?&]?page=(\\d+).*", "$1"));
    var matching =
        records.values().stream().filter(r -> r.path("name").asString().endsWith(suffix)).toList();
    var size = perPage;
    var result = JSON.createArrayNode();
    matching.stream().skip((long) (page - 1) * size).limit(size).forEach(result::add);
    var body = JSON.createObjectNode();
    body.set("result", result);
    body.putObject("result_info")
        .put("page", page)
        .put("total_pages", Math.max(1, (matching.size() + size - 1) / size));
    answer(exchange, 200, body);
  }

  private void answer(HttpExchange exchange, int code, ObjectNode body) throws IOException {
    var failed = status != 200 || code / 100 != 2;
    var answer = failed ? JSON.createObjectNode() : body;
    answer.put("success", !failed);
    var errors = answer.putArray("errors");
    if (failed) {
      errors.addObject().put("code", 10000).put("message", "Authentication error");
    }
    var bytes = answer.toString().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status == 200 ? code : status, bytes.length);
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

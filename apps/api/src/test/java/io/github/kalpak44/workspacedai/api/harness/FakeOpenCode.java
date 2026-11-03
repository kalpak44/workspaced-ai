package io.github.kalpak44.workspacedai.api.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers as {@code opencode serve} does, for the routes the API uses. The event stream replays one
 * captured from opencode itself, so the parsing is tested against real output.
 */
public class FakeOpenCode implements AutoCloseable {

  public static final String PASSWORD = "pw";
  public static final String SESSION = "ses_e5e0d795effeU1Hon4bTR2RHXR";

  public record Request(String method, String path, String directory, String auth, String body) {}

  public final List<Request> requests = new CopyOnWriteArrayList<>();

  /** What GET /session/{id} answers as the title; opencode's placeholder until it names one. */
  public volatile String title = "New session - 2026-11-03T15:09:46.948Z";

  /** When set, the event stream sends one heartbeat and then stays open until released. */
  public volatile CountDownLatch holdStream;

  /**
   * The harness's global MCP configuration, merged into by PATCH /global/config as opencode does.
   */
  public final ObjectNode globalMcp = JSON.createObjectNode();

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpServer server;

  public FakeOpenCode() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
    server.start();
  }

  public HarnessEndpoint endpoint() {
    return new HarnessEndpoint(
        URI.create("http://localhost:" + server.getAddress().getPort()), PASSWORD);
  }

  public HarnessEndpoint wrongPassword() {
    return new HarnessEndpoint(endpoint().baseUrl(), "nope");
  }

  private void handle(HttpExchange exchange) throws IOException {
    var path = exchange.getRequestURI().getPath();
    var method = exchange.getRequestMethod();
    var auth = exchange.getRequestHeaders().getFirst("Authorization");
    requests.add(
        new Request(
            method,
            path,
            exchange.getRequestHeaders().getFirst("x-opencode-directory"),
            auth,
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    // "opencode:pw" — the server rejects anything else, as opencode does.
    if (!"Basic b3BlbmNvZGU6cHc=".equals(auth)) {
      respond(exchange, 401, "");
      return;
    }
    switch (method + " " + path) {
      case "POST /session" ->
          respond(exchange, 200, "{\"id\":\"" + SESSION + "\",\"title\":\"x\"}");
      case "DELETE /session/" + SESSION -> respond(exchange, 200, "true");
      case "GET /session/" + SESSION ->
          respond(exchange, 200, "{\"id\":\"" + SESSION + "\",\"title\":\"" + title + "\"}");
      case "GET /session/" + SESSION + "/message" -> respond(exchange, 200, MESSAGES);
      case "POST /session/" + SESSION + "/prompt_async" -> respond(exchange, 204, null);
      case "POST /session/" + SESSION + "/abort" -> respond(exchange, 200, "true");
      case "GET /event" -> stream(exchange);
      case "GET /config/providers" -> respond(exchange, 200, PROVIDERS);
      case "GET /global/config" -> respond(exchange, 200, globalConfig());
      case "PATCH /global/config" -> {
        // A deep merge, as opencode's: a key the patch leaves out keeps its value.
        var patch = JSON.readTree(requests.getLast().body()).path("mcp");
        patch
            .properties()
            .forEach(
                e -> {
                  var existing = globalMcp.get(e.getKey());
                  if (existing instanceof ObjectNode object) {
                    object.setAll((ObjectNode) e.getValue());
                  } else {
                    globalMcp.set(e.getKey(), e.getValue());
                  }
                });
        respond(exchange, 200, globalConfig());
      }
      case "GET /mcp" -> {
        var statuses = JSON.createObjectNode();
        globalMcp
            .properties()
            .forEach(
                e ->
                    statuses
                        .putObject(e.getKey())
                        .put(
                            "status",
                            e.getValue().path("enabled").asBoolean(true)
                                ? "connected"
                                : "disabled"));
        respond(exchange, 200, JSON.writeValueAsString(statuses));
      }
      case "GET /session/broken/message" -> respond(exchange, 500, "{}");
      default -> respond(exchange, 404, "{}");
    }
  }

  private void stream(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (var body = exchange.getResponseBody()) {
      var hold = holdStream;
      if (hold != null) {
        body.write("data: {\"type\":\"server.heartbeat\",\"properties\":{}}\n\n".getBytes());
        body.flush();
        try {
          hold.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      body.write(Files.readAllBytes(fixture()));
    }
  }

  private static Path fixture() {
    try {
      return Path.of(FakeOpenCode.class.getResource("/opencode-events.txt").toURI());
    } catch (Exception e) {
      throw new UncheckedIOException(new IOException(e));
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /**
   * As opencode answers it: the configured model, and every {env:NAME} in a header already
   * substituted, so a client cannot read the reference back.
   */
  private String globalConfig() {
    var mcp = globalMcp.deepCopy();
    mcp.properties()
        .forEach(
            e -> {
              if (e.getValue().get("headers") instanceof ObjectNode headers) {
                headers
                    .properties()
                    .forEach(
                        h ->
                            headers.put(h.getKey(), h.getValue().asString().replace("{env:", "<")));
              }
            });
    return JSON.writeValueAsString(
        Map.of("model", "openrouter/anthropic/claude-sonnet-5.5", "mcp", mcp));
  }

  /** How many times the global configuration was written. */
  public long patches() {
    return requests.stream().filter(r -> r.method().equals("PATCH")).count();
  }

  @Override
  public void close() {
    var hold = holdStream;
    if (hold != null) {
      hold.countDown();
    }
    server.stop(0);
  }

  // Two that can call tools, one that cannot, and another provider's, in catalogue order.
  private static final String PROVIDERS =
      """
      {"providers":[
        {"id":"openrouter","models":{
          "anthropic/claude-sonnet-5.5":{"id":"anthropic/claude-sonnet-5.5",
            "name":"Claude Sonnet 5.5","limit":{"context":1000000},
            "cost":{"input":2,"output":10},"capabilities":{"toolcall":true}},
          "google/lyria":{"id":"google/lyria","name":"Lyria","capabilities":{"toolcall":false}},
          "anthropic/claude-haiku-5.5":{"id":"anthropic/claude-haiku-5.5",
            "name":"Claude Haiku 5.5","limit":{"context":200000},
            "cost":{"input":1,"output":5},"capabilities":{"toolcall":true}}}},
        {"id":"other","models":{"x":{"id":"x","name":"X","capabilities":{"toolcall":true}}}}],
       "default":{"openrouter":"google/lyria"}}
      """;

  // A user message, then the agent's reply: a step marker, reasoning, a tool call, text, and
  // a synthetic part the chat must not show.
  private static final String MESSAGES =
      """
      [{"info":{"id":"msg_u","role":"user"},
        "parts":[{"id":"prt_u","type":"text","text":"List the files"}]},
       {"info":{"id":"msg_a","role":"assistant"},
        "parts":[{"id":"prt_s","type":"step-start"},
                 {"id":"prt_r","type":"reasoning","text":"I should run ls."},
                 {"id":"prt_t","type":"tool","tool":"bash",
                  "state":{"status":"completed","title":"List files"}},
                 {"id":"prt_x","type":"text","text":"There are none."},
                 {"id":"prt_y","type":"text","text":"hidden","synthetic":true},
                 {"id":"prt_f","type":"step-finish"}]}]
      """;
}

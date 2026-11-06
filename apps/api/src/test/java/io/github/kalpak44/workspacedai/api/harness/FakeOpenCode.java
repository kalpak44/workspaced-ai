package io.github.kalpak44.workspacedai.api.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Answers the OpenCode V2 routes the API uses. */
public class FakeOpenCode implements AutoCloseable {

  public static final String PASSWORD = "pw";
  public static final String SESSION = "ses_e5e0d795effeU1Hon4bTR2RHXR";

  public record Request(String method, String path, String directory, String auth, String body) {}

  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final Map<String, ObjectNode> terminals = new java.util.concurrent.ConcurrentHashMap<>();
  public volatile boolean rejectTerminals;
  private final java.util.concurrent.atomic.AtomicInteger nextTerminal =
      new java.util.concurrent.atomic.AtomicInteger();

  /** Servers added at runtime, as the API did before it kept them in the global config. */
  public final ObjectNode globalMcp = JSON.createObjectNode();

  /** The global opencode.jsonc the API writes MCP servers to; null while it does not exist. */
  public volatile String mcpConfigFile;

  /** Every other file, by absolute path, as the file API reads and writes them. */
  public final Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();

  public final List<String> shellCommands = new CopyOnWriteArrayList<>();
  public volatile int shellExit;
  public volatile int reloads;

  /** Lists no MCP servers, as OpenCode does while a reload is still starting them. */
  public volatile boolean mcpStarting;

  public volatile String title;
  public volatile String agent = "build";
  public volatile boolean rejectAgentSwitch;
  public volatile boolean rejectAgentList;
  public volatile boolean rejectRevert;
  public volatile CountDownLatch holdStream;
  public volatile boolean sessionExists = true;
  public volatile Long sessionUpdatedAt = 1793718388385L;

  /** Runs while the session list is being answered, as another request would meanwhile. */
  public volatile Runnable whileListingSessions;

  /** Every page points at another that repeats the session, as for one updated mid-listing. */
  public volatile boolean sessionBusy;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CONFIG_FILE = "/home/workspace/.config/opencode/opencode.jsonc";
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
            query(exchange.getRequestURI(), "location[directory]"),
            auth,
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    if (!"Basic b3BlbmNvZGU6cHc=".equals(auth)) {
      respond(exchange, 401, "");
      return;
    }
    if (path.equals("/api/pty") || path.startsWith("/api/pty/")) {
      if (rejectTerminals) {
        respond(exchange, 503, "{}");
        return;
      }
      var directory = query(exchange.getRequestURI(), "location[directory]");
      var id = path.substring(path.lastIndexOf('/') + 1);
      var key = directory + ":" + id;
      if (path.equals("/api/pty") && method.equals("GET")) {
        var data = JSON.createArrayNode();
        terminals.forEach(
            (own, value) -> {
              if (own.startsWith(directory + ":")) data.add(value);
            });
        respond(exchange, 200, JSON.writeValueAsString(Map.of("data", data)));
      } else if (path.equals("/api/pty") && method.equals("POST")) {
        var body = JSON.readTree(requests.getLast().body());
        var terminal =
            JSON.createObjectNode()
                .put("id", "pty_" + nextTerminal.incrementAndGet())
                .put("title", body.path("title").asString())
                .put("command", body.path("command").asString())
                .put("cwd", body.path("cwd").asString())
                .put("status", "running")
                .put("pid", 123);
        terminal.set("args", body.path("args"));
        terminals.put(directory + ":" + terminal.path("id").asString(), terminal);
        respond(exchange, 200, JSON.writeValueAsString(Map.of("data", terminal)));
      } else if (method.equals("DELETE")) {
        respond(exchange, terminals.remove(key) == null ? 404 : 204, null);
      } else if (method.equals("GET") && terminals.containsKey(key)) {
        respond(exchange, 200, JSON.writeValueAsString(Map.of("data", terminals.get(key))));
      } else if (method.equals("PUT") && terminals.containsKey(key)) {
        respond(exchange, 200, JSON.writeValueAsString(Map.of("data", terminals.get(key))));
      } else {
        respond(exchange, 404, "{}");
      }
      return;
    }
    if (method.equals("PUT") && path.startsWith("/api/experimental/mcp/")) {
      var name = path.substring(path.lastIndexOf('/') + 1);
      globalMcp.set(name, JSON.readTree(requests.getLast().body()).path("config"));
      respond(exchange, 204, null);
      return;
    }
    if (method.equals("DELETE") && path.startsWith("/api/experimental/mcp/")) {
      globalMcp.remove(path.substring(path.lastIndexOf('/') + 1));
      respond(exchange, 204, null);
      return;
    }
    if (method.equals("POST") && path.equals("/api/shell")) {
      var command = JSON.readTree(requests.getLast().body()).path("command").asString();
      shellCommands.add(command);
      if (command.startsWith("rm -f -- '") && command.endsWith("'")) {
        files.remove(command.substring("rm -f -- '".length(), command.length() - 1));
      }
      respond(exchange, 200, "{\"data\":{\"id\":\"sh_1\",\"status\":\"running\"}}");
      return;
    }
    switch (method + " " + path) {
      case "GET /api/info" ->
          respond(
              exchange,
              200,
              "{\"version\":\"2.2.5\",\"pid\":1,\"urls\":[],\"paths\":{\"tmp\":\"/tmp\"}}");
      case "GET /api/location" ->
          respond(
              exchange,
              200,
              "{\"directory\":\"/home/workspace/project\",\"project\":{\"id\":\"global\","
                  + "\"directory\":\"/home/workspace/project\",\"canonical\":\"/home/workspace/project\"}}");
      case "GET /api/session" -> {
        var meanwhile = whileListingSessions;
        if (meanwhile != null) {
          meanwhile.run();
        }
        respond(exchange, 200, sessions(exchange.getRequestURI().getRawQuery()));
      }
      case "POST /api/session" -> {
        sessionExists = true;
        respond(exchange, 200, "{\"data\":" + session() + "}");
      }
      case "DELETE /api/session/" + SESSION -> {
        sessionExists = false;
        respond(exchange, 204, null);
      }
      case "DELETE /api/session/ses_gone" -> respond(exchange, 404, "{}");
      case "GET /api/session/" + SESSION -> respond(exchange, 200, "{\"data\":" + session() + "}");
      case "GET /api/agent" -> respond(exchange, rejectAgentList ? 503 : 200, AGENTS);
      case "POST /api/session/" + SESSION + "/agent" -> {
        if (rejectAgentSwitch) {
          respond(exchange, 503, "{}");
        } else {
          agent = JSON.readTree(requests.getLast().body()).path("agent").asString();
          respond(exchange, 204, null);
        }
      }
      case "GET /api/session/" + SESSION + "/message" -> respond(exchange, 200, MESSAGES);
      case "GET /api/session/" + SESSION + "/form" ->
          respond(
              exchange,
              200,
              "{\"data\":[{\"id\":\"frm_age\",\"sessionID\":\""
                  + SESSION
                  + "\",\"title\":\"Your age\",\"fields\":[{\"key\":\"age\",\"type\":\"integer\",\"required\":true}]}]}");
      case "POST /api/session/" + SESSION + "/form/frm_age/reply",
          "DELETE /api/session/" + SESSION + "/form/frm_age" ->
          respond(exchange, 204, null);
      case "GET /api/session/broken/message" -> respond(exchange, 500, "{}");
      case "POST /api/session/" + SESSION + "/model" -> respond(exchange, 204, null);
      case "POST /api/session/" + SESSION + "/prompt" ->
          respond(exchange, 200, "{\"data\":{\"id\":\"msg_u\"}}");
      case "POST /api/session/" + SESSION + "/revert/stage" ->
          respond(exchange, rejectRevert ? 409 : 200, "{}");
      case "POST /api/session/" + SESSION + "/revert/commit" -> respond(exchange, 204, null);
      case "POST /api/session/" + SESSION + "/interrupt" -> respond(exchange, 204, null);
      case "GET /api/event" -> stream(exchange);
      case "GET /api/model" -> respond(exchange, 200, MODELS);
      case "GET /api/model/default" ->
          respond(
              exchange,
              200,
              "{\"location\":{\"directory\":\"/home/workspace/project\"},"
                  + "\"data\":{\"id\":\"anthropic/claude-sonnet-5.5\",\"providerID\":\"openrouter\"}}");
      case "GET /api/mcp" -> respond(exchange, 200, mcp());
      case "GET /api/shell/sh_1" ->
          respond(
              exchange,
              200,
              "{\"data\":{\"id\":\"sh_1\",\"status\":\"exited\",\"exit\":" + shellExit + "}}");
      case "GET /api/fs/read/opencode.jsonc" -> {
        var config = mcpConfigFile;
        respond(exchange, config == null ? 404 : 200, config == null ? "{}" : config);
      }
      case "POST /api/experimental/fs/write" -> {
        var written = query(exchange.getRequestURI(), "path");
        if (CONFIG_FILE.equals(written)) {
          mcpConfigFile = requests.getLast().body();
        } else {
          files.put(written, requests.getLast().body());
        }
        respond(exchange, 200, "{\"data\":{}}");
      }
      case "POST /api/location/reload" -> {
        reloads++;
        respond(exchange, 204, null);
      }
      case "DELETE /api/debug/location" -> respond(exchange, 204, null);
      default -> {
        if ((method + " " + path).equals("GET /api/fs/list")) {
          var directory = query(exchange.getRequestURI(), "location[directory]") + "/";
          var entries =
              files.keySet().stream()
                  .filter(
                      f ->
                          f.startsWith(directory) && !f.substring(directory.length()).contains("/"))
                  .sorted()
                  .map(f -> Map.of("path", f.substring(directory.length()), "type", "file"))
                  .toList();
          respond(exchange, 200, JSON.writeValueAsString(Map.of("data", entries)));
        } else if ((method + " " + path).startsWith("GET /api/fs/read/")) {
          var file =
              query(exchange.getRequestURI(), "location[directory]")
                  + "/"
                  + path.substring("/api/fs/read/".length());
          var content = files.get(file);
          respond(exchange, content == null ? 404 : 200, content == null ? "{}" : content);
        } else {
          respond(exchange, 404, "{}");
        }
      }
    }
  }

  private String sessions(String query) {
    var next = "null";
    if (sessionBusy) {
      next = "\"later\"";
      if (query.contains("cursor=")) {
        title = "Renamed while listing";
      }
    }
    return "{\"data\":"
        + (sessionExists ? "[" + session() + "]" : "[]")
        + ",\"cursor\":{\"previous\":null,\"next\":"
        + next
        + "}}";
  }

  private String session() {
    var named = title == null ? "" : ",\"title\":" + JSON.writeValueAsString(title);
    return "{\"id\":\""
        + SESSION
        + "\",\"projectID\":\"global\",\"cost\":0,"
        + "\"tokens\":{},\"time\":{\"created\":1793718388385,\"updated\":"
        + sessionUpdatedAt
        + "},"
        + "\"location\":{\"directory\":\"/home/workspace/project\"}"
        + (agent == null ? "" : ",\"agent\":" + JSON.writeValueAsString(agent))
        + named
        + "}";
  }

  private static final String AGENTS =
      """
      {"data":[
        {"id":"build","name":"Build","description":"Implement changes","mode":"primary","hidden":false},
        {"id":"plan","name":"Plan","description":"Plan before implementation","mode":"primary","hidden":false},
        {"id":"writer","name":"Writer","description":"Write documentation","mode":"all","hidden":false},
        {"id":"explore","name":"Explore","mode":"subagent","hidden":false},
        {"id":"title","name":"Title","mode":"primary","hidden":true}
      ]}
      """;

  /** The servers in the global config file, by name. */
  public ObjectNode configuredMcp() {
    var config = mcpConfigFile;
    return config != null
            && JSON.readTree(config).path("mcp").path("servers") instanceof ObjectNode servers
        ? servers
        : JSON.createObjectNode();
  }

  private String mcp() {
    var data = JSON.createArrayNode();
    var servers = configuredMcp();
    servers.setAll(globalMcp);
    if (mcpStarting) {
      servers.removeAll();
    }
    servers
        .properties()
        .forEach(
            entry ->
                data.addObject()
                    .put("name", entry.getKey())
                    .putObject("status")
                    .put("status", "connected"));
    return JSON.writeValueAsString(
        Map.of("location", Map.of("directory", "/home/workspace/project"), "data", data));
  }

  private void stream(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (var body = exchange.getResponseBody()) {
      body.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
      body.flush();
      var hold = holdStream;
      if (hold != null) {
        try {
          hold.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      body.write(EVENTS.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String query(URI uri, String name) {
    var query = uri.getRawQuery();
    if (query == null) {
      return null;
    }
    for (var pair : query.split("&")) {
      var parts = pair.split("=", 2);
      if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
        return parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      }
    }
    return null;
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

  @Override
  public void close() {
    var hold = holdStream;
    if (hold != null) {
      hold.countDown();
    }
    server.stop(0);
  }

  private static final String MODELS =
      """
      {"location":{"directory":"/home/workspace/project"},"data":[
        {"id":"anthropic/claude-sonnet-5.5","modelID":"anthropic/claude-sonnet-5.5",
         "providerID":"openrouter","name":"Claude Sonnet 5.5","type":"chat","enabled":true,
         "status":"active","variants":[],"time":{"released":1},
         "limit":{"context":1000000,"output":32000},
         "capabilities":{"tools":true,"input":["text"],"output":["text"]},
         "cost":[{"input":2,"output":10,"cache":{"read":0,"write":0}}]},
        {"id":"google/lyria","modelID":"google/lyria","providerID":"openrouter",
         "name":"Lyria","type":"image","enabled":true,"status":"active","variants":[],
         "time":{"released":1},"limit":{"context":1,"output":1},
         "capabilities":{"tools":false,"input":["text"],"output":["image"]},"cost":[]},
        {"id":"anthropic/claude-haiku-5.5","modelID":"anthropic/claude-haiku-5.5",
         "providerID":"openrouter","name":"Claude Haiku 5.5","type":"chat","enabled":true,
         "status":"active","variants":[],"time":{"released":1},
         "limit":{"context":200000,"output":16000},
         "capabilities":{"tools":true,"input":["text"],"output":["text"]},
         "cost":[{"input":1,"output":5,"cache":{"read":0,"write":0}}]}]}
      """;

  private static final String MESSAGES =
      """
      {"data":[
        {"id":"msg_u","type":"user","time":{"created":1},"text":"List the files"},
        {"id":"msg_a","type":"assistant","time":{"created":2},"agent":"build",
         "model":{"id":"x","providerID":"openrouter"},"content":[
          {"type":"reasoning","text":"I should run ls."},
          {"type":"tool","id":"tool_1","name":"bash","executed":true,"time":{"created":2},
           "state":{"status":"completed","input":{},"content":[{"type":"text","text":""}]}},
          {"type":"text","text":"There are none."}]}],
       "cursor":{"previous":null,"next":null}}
      """;

  private static final String EVENTS =
      """
      data: {"type":"session.inbox.delivered","data":{"sessionID":"%s","inboxID":"msg_u"}}

      data: {"type":"session.execution.started","data":{"sessionID":"%s"}}

      data: {"type":"session.renamed","data":{"sessionID":"%s","title":"Friendly greeting request"}}

      data: {"type":"session.text.started","data":{"sessionID":"%s","assistantMessageID":"msg_a","ordinal":0}}

      data: {"type":"session.text.delta","data":{"sessionID":"%s","assistantMessageID":"msg_a","ordinal":0,"delta":"second answer here"}}

      data: {"type":"session.text.ended","data":{"sessionID":"%s","assistantMessageID":"msg_a","ordinal":0,"text":"second answer here"}}

      data: {"type":"session.execution.succeeded","data":{"sessionID":"%s"}}

      """
          .formatted(SESSION, SESSION, SESSION, SESSION, SESSION, SESSION, SESSION);
}

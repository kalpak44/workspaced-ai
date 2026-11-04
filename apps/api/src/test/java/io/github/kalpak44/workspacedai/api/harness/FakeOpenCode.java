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
  public final ObjectNode globalMcp = JSON.createObjectNode();
  public volatile String title;
  public volatile CountDownLatch holdStream;
  public volatile boolean sessionExists = true;

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
            query(exchange.getRequestURI(), "location[directory]"),
            auth,
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    if (!"Basic b3BlbmNvZGU6cHc=".equals(auth)) {
      respond(exchange, 401, "");
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
      case "GET /api/session" -> respond(exchange, 200, sessions());
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
      case "GET /api/session/" + SESSION + "/message" -> respond(exchange, 200, MESSAGES);
      case "GET /api/session/broken/message" -> respond(exchange, 500, "{}");
      case "POST /api/session/" + SESSION + "/model" -> respond(exchange, 204, null);
      case "POST /api/session/" + SESSION + "/prompt" ->
          respond(exchange, 200, "{\"data\":{\"id\":\"msg_u\"}}");
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
      default -> respond(exchange, 404, "{}");
    }
  }

  private String sessions() {
    return "{\"data\":"
        + (sessionExists ? "[" + session() + "]" : "[]")
        + ",\"cursor\":{\"previous\":null,\"next\":null}}";
  }

  private String session() {
    var named = title == null ? "" : ",\"title\":" + JSON.writeValueAsString(title);
    return "{\"id\":\""
        + SESSION
        + "\",\"projectID\":\"global\",\"cost\":0,"
        + "\"tokens\":{},\"time\":{\"created\":1793718388385,\"updated\":1793718388385},"
        + "\"location\":{\"directory\":\"/home/workspace/project\"}"
        + named
        + "}";
  }

  private String mcp() {
    var data = JSON.createArrayNode();
    globalMcp
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

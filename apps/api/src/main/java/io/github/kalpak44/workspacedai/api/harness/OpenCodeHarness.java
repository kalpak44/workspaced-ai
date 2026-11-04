package io.github.kalpak44.workspacedai.api.harness;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Talks only to the OpenCode V2 HTTP API. */
@Component
public class OpenCodeHarness implements Harness {

  private static final Logger LOG = LoggerFactory.getLogger(OpenCodeHarness.class);
  private static final String USER = "opencode";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final String EVENT_TYPES =
      String.join(
          ",",
          "session.renamed",
          "session.inbox.delivered",
          "session.execution.started",
          "session.execution.succeeded",
          "session.execution.failed",
          "session.execution.interrupted",
          "session.text.started",
          "session.text.delta",
          "session.text.ended",
          "session.reasoning.started",
          "session.reasoning.delta",
          "session.reasoning.ended",
          "session.tool.input.started",
          "session.tool.called",
          "session.tool.progress",
          "session.tool.success",
          "session.tool.failed");

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(CONNECT_TIMEOUT)
          .build();
  private final JsonMapper json;

  public OpenCodeHarness(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void validate(HarnessEndpoint endpoint, String directory) {
    var info = call(endpoint, "GET", "/api/info", null);
    var version = text(info, "version");
    if (version == null || !version.startsWith("2.")) {
      throw new HarnessUnavailableException("the workspace is not running OpenCode V2");
    }
    call(endpoint, "GET", withLocation("/api/location", directory), null);
  }

  @Override
  public List<HarnessSession> sessions(HarnessEndpoint endpoint) {
    var result = new ArrayList<HarnessSession>();
    String cursor = null;
    do {
      var path = "/api/session?limit=100&parentID=null";
      if (cursor != null) {
        path += "&cursor=" + encode(cursor);
      } else {
        path += "&order=asc";
      }
      var page = call(endpoint, "GET", path, null);
      for (var session : page.path("data")) {
        var created = number(session.path("time"), "created");
        result.add(
            new HarnessSession(
                text(session, "id"),
                text(session, "title"),
                text(session.path("location"), "directory"),
                created == null ? Instant.now() : instant(created)));
      }
      cursor = nullableText(page.path("cursor"), "next");
    } while (cursor != null);
    return result;
  }

  @Override
  public String createSession(HarnessEndpoint endpoint, String directory, String title) {
    var body = new LinkedHashMap<String, Object>();
    body.put("location", Map.of("directory", directory));
    if (title != null) {
      body.put("title", title);
    }
    return text(call(endpoint, "POST", "/api/session", body).path("data"), "id");
  }

  @Override
  public void deleteSession(HarnessEndpoint endpoint, String directory, String sessionId) {
    var response = send(request(endpoint, "/api/session/" + encode(sessionId)).DELETE());
    if (response.statusCode() != 404) {
      check(response);
    }
  }

  @Override
  public List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId) {
    var result = new ArrayList<ChatMessage>();
    String cursor = null;
    do {
      var path = "/api/session/" + encode(sessionId) + "/message?limit=100";
      if (cursor != null) {
        path += "&cursor=" + encode(cursor);
      } else {
        path += "&order=asc";
      }
      var page = call(endpoint, "GET", path, null);
      for (var message : page.path("data")) {
        var mapped = message(message);
        if (mapped != null) {
          result.add(mapped);
        }
      }
      cursor = nullableText(page.path("cursor"), "next");
    } while (cursor != null);
    return result;
  }

  @Override
  public Optional<String> title(HarnessEndpoint endpoint, String directory, String sessionId) {
    var response = call(endpoint, "GET", "/api/session/" + encode(sessionId), null);
    return Optional.ofNullable(text(response.path("data"), "title"));
  }

  @Override
  public void prompt(
      HarnessEndpoint endpoint, String directory, String sessionId, String text, ModelRef model) {
    if (model != null) {
      call(
          endpoint,
          "POST",
          "/api/session/" + encode(sessionId) + "/model",
          Map.of("model", Map.of("providerID", model.providerId(), "id", model.modelId())));
    }
    call(endpoint, "POST", "/api/session/" + encode(sessionId) + "/prompt", Map.of("text", text));
  }

  @Override
  public ModelCatalogue models(HarnessEndpoint endpoint, String providerId) {
    var response = call(endpoint, "GET", withLocation("/api/model", PROJECT_ROOT), null);
    var models = new ArrayList<ModelOption>();
    for (var model : response.path("data")) {
      if (!providerId.equals(text(model, "providerID"))
          || !model.path("enabled").asBoolean(true)
          || !model.path("capabilities").path("tools").asBoolean(false)) {
        continue;
      }
      var costs = model.path("cost");
      var cost = costs.isArray() && !costs.isEmpty() ? costs.get(0) : json.missingNode();
      models.add(
          new ModelOption(
              text(model, "id"),
              text(model, "name"),
              longNumber(model.path("limit"), "context"),
              number(cost, "input"),
              number(cost, "output")));
    }
    models.sort(Comparator.comparing(ModelOption::name, String.CASE_INSENSITIVE_ORDER));
    var defaultModel =
        call(endpoint, "GET", withLocation("/api/model/default", PROJECT_ROOT), null);
    return new ModelCatalogue(text(defaultModel.path("data"), "id"), models);
  }

  @Override
  public Map<String, String> mcpStatus(HarnessEndpoint endpoint) {
    var statuses = new TreeMap<String, String>();
    for (var server :
        call(endpoint, "GET", withLocation("/api/mcp", PROJECT_ROOT), null).path("data")) {
      statuses.put(text(server, "name"), text(server.path("status"), "status"));
    }
    return statuses;
  }

  @Override
  public synchronized boolean configureMcp(
      HarnessEndpoint endpoint, Map<String, McpServerConfig> servers) {
    var document = mcpDocument(endpoint);
    var mcp = document.withObject("/mcp");
    servers.forEach(
        (name, server) ->
            mcp.set(
                name,
                json.valueToTree(
                    Map.of("type", "remote", "url", server.url(), "headers", server.headers()))));
    if (!servers.isEmpty()) saveMcpDocument(endpoint, document);
    return !servers.isEmpty();
  }

  private ObjectNode mcpDocument(HarnessEndpoint endpoint) {
    var response =
        send(
            request(endpoint, withLocation("/api/fs/read/.opencode/opencode.json", PROJECT_ROOT))
                .GET());
    if (response.statusCode() == 404) return json.createObjectNode();
    check(response);
    var document =
        JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()
            .readTree(response.body());
    if (!(document instanceof ObjectNode object)) {
      throw new HarnessUnavailableException("OpenCode project configuration must be a JSON object");
    }
    return object;
  }

  private void saveMcpDocument(HarnessEndpoint endpoint, ObjectNode document) {
    check(
        send(
            request(
                    endpoint,
                    withLocation("/api/experimental/fs/write", PROJECT_ROOT)
                        + "&path="
                        + encode(".opencode/opencode.json"))
                .header("Content-Type", "application/octet-stream")
                .POST(BodyPublishers.ofString(json.writeValueAsString(document)))));
    call(endpoint, "POST", "/api/location/reload", Map.of());
  }

  @Override
  public Map<String, McpServerConfig> mcpConfiguration(HarnessEndpoint endpoint) {
    var result = new TreeMap<String, McpServerConfig>();
    mcpDocument(endpoint)
        .path("mcp")
        .properties()
        .forEach(
            entry -> {
              var config = entry.getValue();
              if (!"remote".equals(text(config, "type"))) return;
              var headers = new TreeMap<String, String>();
              config
                  .path("headers")
                  .properties()
                  .forEach(h -> headers.put(h.getKey(), h.getValue().asString()));
              result.put(entry.getKey(), new McpServerConfig(text(config, "url"), headers));
            });
    return result;
  }

  @Override
  public synchronized void removeMcp(HarnessEndpoint endpoint, String name) {
    var document = mcpDocument(endpoint);
    if (document.path("mcp") instanceof ObjectNode mcp) {
      mcp.remove(name);
      saveMcpDocument(endpoint, document);
    }
  }

  @Override
  public void abort(HarnessEndpoint endpoint, String directory, String sessionId) {
    call(endpoint, "POST", "/api/session/" + encode(sessionId) + "/interrupt", Map.of());
  }

  @Override
  public WorkspaceTerminal terminal(
      HarnessEndpoint endpoint, String directory, OutputStream output, Runnable onExit) {
    validate(endpoint, directory);
    var created =
        call(
                endpoint,
                "POST",
                withLocation("/api/pty", directory),
                Map.of(
                    "command",
                    "/bin/bash",
                    "args",
                    List.of("-l"),
                    "cwd",
                    directory,
                    "title",
                    "Workspace terminal"))
            .path("data");
    var ptyId = text(created, "id");
    var tokenResponse =
        check(
            send(
                request(
                        endpoint,
                        withLocation("/api/pty/" + encode(ptyId) + "/connect-token", directory))
                    .header("x-opencode-ticket", "1")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{}"))));
    var ticket = text(json.readTree(tokenResponse.body()).path("data"), "ticket");
    var socketUri =
        websocket(
            endpoint.baseUrl(),
            withLocation("/api/pty/" + encode(ptyId) + "/connect", directory)
                + "&ticket="
                + encode(ticket));
    var closed = new AtomicBoolean();
    var socket =
        http.newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(socketUri, new TerminalListener(output, closed, onExit))
            .join();
    return new WorkspaceTerminal() {
      @Override
      public void input(byte[] bytes) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
      }

      @Override
      public void resize(int columns, int rows) {
        call(
            endpoint,
            "PUT",
            withLocation("/api/pty/" + encode(ptyId), directory),
            Map.of("size", Map.of("cols", columns, "rows", rows)));
      }

      @Override
      public void close() {
        if (closed.compareAndSet(false, true)) {
          socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
          var response =
              send(
                  request(endpoint, withLocation("/api/pty/" + encode(ptyId), directory)).DELETE());
          if (response.statusCode() != 404) {
            check(response);
          }
        }
      }
    };
  }

  @Override
  public AutoCloseable stream(
      HarnessEndpoint endpoint, Consumer<HarnessEventEnvelope> onEvent, Runnable onEnd) {
    var closed = new AtomicBoolean();
    var body = new AtomicReference<InputStream>();
    var request =
        request(endpoint, "/api/event?types=" + EVENT_TYPES.replace(",", "&types="))
            .timeout(Duration.ofDays(1))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var reader =
        Thread.ofVirtual()
            .name("opencode-v2-events")
            .start(
                () -> {
                  try {
                    var response = http.send(request, BodyHandlers.ofInputStream());
                    body.set(response.body());
                    if (response.statusCode() / 100 != 2) {
                      throw new IOException("event stream answered " + response.statusCode());
                    }
                    read(response.body(), onEvent, closed);
                  } catch (IOException e) {
                    if (!closed.get()) {
                      LOG.info("OpenCode V2 event stream ended: {}", e.getMessage());
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    if (!closed.get()) {
                      onEnd.run();
                    }
                  }
                });
    return () -> {
      if (closed.compareAndSet(false, true)) {
        var stream = body.get();
        if (stream != null) {
          stream.close();
        }
        reader.interrupt();
      }
    };
  }

  private void read(InputStream body, Consumer<HarnessEventEnvelope> onEvent, AtomicBoolean closed)
      throws IOException {
    var tools = new TreeMap<String, String>();
    try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (!closed.get() && (line = lines.readLine()) != null) {
        if (line.startsWith(":")) {
          onEvent.accept(new HarnessEventEnvelope(null, new HarnessEvent.Heartbeat()));
        } else if (line.startsWith("data:")) {
          events(json.readTree(line.substring(5)), tools).forEach(onEvent);
        }
      }
    }
  }

  static List<HarnessEventEnvelope> events(JsonNode event, Map<String, String> tools) {
    var type = text(event, "type");
    var data = event.path("data");
    var sessionId = text(data, "sessionID");
    if (type == null || sessionId == null) {
      return List.of();
    }
    var result = new ArrayList<HarnessEvent>();
    switch (type) {
      case "session.renamed" -> result.add(new HarnessEvent.Title(text(data, "title")));
      case "session.inbox.delivered" -> result.add(new HarnessEvent.Refresh());
      case "session.execution.started" -> result.add(new HarnessEvent.Status(true));
      case "session.execution.succeeded", "session.execution.interrupted" ->
          result.add(new HarnessEvent.Status(false));
      case "session.execution.failed" -> {
        result.add(new HarnessEvent.Error(error(data.path("error"))));
        result.add(new HarnessEvent.Status(false));
      }
      case "session.text.started", "session.reasoning.started" -> {
        var messageId = text(data, "assistantMessageID");
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(new HarnessEvent.Message(messageId, "assistant"));
        result.add(
            new HarnessEvent.Part(
                messageId, new ChatPart(partId(partType, data), partType, "", null, null)));
      }
      case "session.text.delta", "session.reasoning.delta" -> {
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(
            new HarnessEvent.Delta(
                text(data, "assistantMessageID"), partId(partType, data), text(data, "delta")));
      }
      case "session.text.ended", "session.reasoning.ended" -> {
        var partType = type.contains("reasoning") ? "reasoning" : "text";
        result.add(
            new HarnessEvent.Part(
                text(data, "assistantMessageID"),
                new ChatPart(partId(partType, data), partType, text(data, "text"), null, null)));
      }
      case "session.tool.input.started" -> {
        var id = text(data, "id");
        var name = text(data, "name");
        tools.put(id, name);
        result.add(
            new HarnessEvent.Part(
                text(data, "assistantMessageID"), new ChatPart(id, "tool", name, name, "pending")));
      }
      case "session.tool.called", "session.tool.progress" -> {
        var id = text(data, "id");
        var name = tools.get(id);
        result.add(
            new HarnessEvent.Part(
                text(data, "assistantMessageID"), new ChatPart(id, "tool", name, name, "running")));
      }
      case "session.tool.success", "session.tool.failed" -> {
        var id = text(data, "id");
        var name = tools.remove(id);
        result.add(
            new HarnessEvent.Part(
                text(data, "assistantMessageID"),
                new ChatPart(
                    id, "tool", name, name, type.endsWith("success") ? "completed" : "error")));
      }
      default -> {
        return List.of();
      }
    }
    return result.stream().map(e -> new HarnessEventEnvelope(sessionId, e)).toList();
  }

  static ChatMessage message(JsonNode message) {
    var id = text(message, "id");
    var type = text(message, "type");
    if ("user".equals(type)) {
      return new ChatMessage(
          id,
          "user",
          List.of(new ChatPart(id + ":text", "text", text(message, "text"), null, null)));
    }
    if (!"assistant".equals(type)) {
      return null;
    }
    var parts = new ArrayList<ChatPart>();
    var ordinal = 0;
    for (var content : message.path("content")) {
      var mapped = part(id, ordinal++, content);
      if (mapped != null) {
        parts.add(mapped);
      }
    }
    return new ChatMessage(id, "assistant", parts);
  }

  private static ChatPart part(String messageId, int ordinal, JsonNode content) {
    var type = text(content, "type");
    if ("text".equals(type) || "reasoning".equals(type)) {
      return new ChatPart(
          messageId + ":" + type + ":" + ordinal, type, text(content, "text"), null, null);
    }
    if (!"tool".equals(type)) {
      return null;
    }
    var name = text(content, "name");
    var status = text(content.path("state"), "status");
    if ("streaming".equals(status)) {
      status = "pending";
    }
    return new ChatPart(text(content, "id"), "tool", name, name, status);
  }

  private static String partId(String type, JsonNode data) {
    return text(data, "assistantMessageID") + ":" + type + ":" + data.path("ordinal").asInt();
  }

  private JsonNode call(HarnessEndpoint endpoint, String method, String path, Object body) {
    var publisher =
        body == null
            ? BodyPublishers.noBody()
            : BodyPublishers.ofString(json.writeValueAsString(body));
    var response =
        check(
            send(
                request(endpoint, path)
                    .header("Content-Type", "application/json")
                    .method(method, publisher)));
    return response.body() == null || response.body().isBlank()
        ? json.nullNode()
        : json.readTree(response.body());
  }

  private HttpRequest.Builder request(HarnessEndpoint endpoint, String path) {
    var credentials = USER + ":" + endpoint.password();
    return HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
        .timeout(REQUEST_TIMEOUT)
        .header(
            "Authorization",
            "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new HarnessUnavailableException("the workspace's OpenCode service is not answering", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarnessUnavailableException("interrupted calling OpenCode", e);
    }
  }

  private static HttpResponse<String> check(HttpResponse<String> response) {
    if (response.statusCode() / 100 != 2) {
      throw new HarnessUnavailableException("OpenCode V2 answered " + response.statusCode());
    }
    return response;
  }

  private static String withLocation(String path, String directory) {
    return path + (path.contains("?") ? "&" : "?") + "location%5Bdirectory%5D=" + encode(directory);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static URI websocket(URI base, String path) {
    var scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
    return URI.create(scheme + "://" + base.getAuthority() + path);
  }

  private static Instant instant(Number millis) {
    return Instant.ofEpochMilli(millis.longValue());
  }

  private static Long longNumber(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  private static Double number(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asDouble();
  }

  private static String nullableText(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String text(JsonNode node, String field) {
    return nullableText(node, field);
  }

  private static String error(JsonNode error) {
    var message = text(error, "message");
    if (message == null) {
      message = text(error.path("data"), "message");
    }
    return message == null ? "The reply failed" : message;
  }

  private static final class TerminalListener implements WebSocket.Listener {
    private final OutputStream output;
    private final AtomicBoolean closed;
    private final Runnable onExit;

    private TerminalListener(OutputStream output, AtomicBoolean closed, Runnable onExit) {
      this.output = output;
      this.closed = closed;
      this.onExit = onExit;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      write(data.toString().getBytes(StandardCharsets.UTF_8));
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      if (bytes.length == 0 || bytes[0] != 0) {
        write(bytes);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      exited();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      exited();
    }

    private void write(byte[] bytes) {
      try {
        synchronized (output) {
          output.write(bytes);
          output.flush();
        }
      } catch (IOException e) {
        throw new HarnessUnavailableException("could not relay terminal output", e);
      }
    }

    private void exited() {
      if (closed.compareAndSet(false, true)) {
        onExit.run();
      }
    }
  }
}

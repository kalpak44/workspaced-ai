package io.github.kalpak44.workspacedai.api.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Talks to {@code opencode serve} over its HTTP API. */
@Component
public class OpenCodeHarness implements Harness {

  private static final Logger LOG = LoggerFactory.getLogger(OpenCodeHarness.class);

  // opencode scopes each request, the event stream included, to this directory.
  static final String DIRECTORY_HEADER = "x-opencode-directory";
  private static final String USER = "opencode";
  // What opencode calls a session until it generates a title from the first exchange.
  private static final Pattern PLACEHOLDER_TITLE =
      Pattern.compile("^(New|Child) session - \\d{4}-\\d{2}-\\d{2}T.*");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  // Long enough for a busy harness to answer a plain request; replies themselves stream.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  private final JsonMapper json;

  public OpenCodeHarness(JsonMapper json) {
    this.json = json;
  }

  @Override
  public String createSession(HarnessEndpoint endpoint, String directory, String title) {
    var body = title == null ? Map.of() : Map.of("title", title);
    var created = call(endpoint, directory, "POST", "/session", body);
    return created.get("id").asString();
  }

  @Override
  public void deleteSession(HarnessEndpoint endpoint, String directory, String sessionId) {
    var response = send(request(endpoint, directory, "/session/" + sessionId).DELETE());
    if (response.statusCode() != 404) {
      check(response);
    }
  }

  @Override
  public List<ChatMessage> messages(HarnessEndpoint endpoint, String directory, String sessionId) {
    var messages = new ArrayList<ChatMessage>();
    for (var message :
        call(endpoint, directory, "GET", "/session/" + sessionId + "/message", null)) {
      var info = message.path("info");
      var parts = new ArrayList<ChatPart>();
      for (var part : message.path("parts")) {
        var mapped = part(part);
        if (mapped != null) {
          parts.add(mapped);
        }
      }
      messages.add(new ChatMessage(text(info, "id"), text(info, "role"), parts));
    }
    return messages;
  }

  @Override
  public Optional<String> title(HarnessEndpoint endpoint, String directory, String sessionId) {
    return Optional.ofNullable(
            text(call(endpoint, directory, "GET", "/session/" + sessionId, null), "title"))
        .filter(title -> !PLACEHOLDER_TITLE.matcher(title).matches());
  }

  @Override
  public void prompt(
      HarnessEndpoint endpoint, String directory, String sessionId, String text, ModelRef model) {
    var body = new LinkedHashMap<String, Object>();
    if (model != null) {
      body.put("model", Map.of("providerID", model.providerId(), "modelID", model.modelId()));
    }
    body.put("parts", List.of(Map.of("type", "text", "text", text)));
    call(endpoint, directory, "POST", "/session/" + sessionId + "/prompt_async", body);
  }

  @Override
  public ModelCatalogue models(HarnessEndpoint endpoint, String providerId) {
    var catalogue = call(endpoint, HOME, "GET", "/config/providers", null);
    var models = new ArrayList<ModelOption>();
    for (var provider : catalogue.path("providers")) {
      if (!providerId.equals(text(provider, "id"))) {
        continue;
      }
      for (var model : provider.path("models")) {
        if (model.path("capabilities").path("toolcall").asBoolean(false)) {
          var cost = model.path("cost");
          models.add(
              new ModelOption(
                  text(model, "id"),
                  text(model, "name"),
                  number(model.path("limit"), "context") == null
                      ? null
                      : number(model.path("limit"), "context").longValue(),
                  number(cost, "input"),
                  number(cost, "output")));
        }
      }
    }
    models.sort(Comparator.comparing(ModelOption::name, String.CASE_INSENSITIVE_ORDER));
    return new ModelCatalogue(configuredModel(endpoint, providerId, catalogue), models);
  }

  /**
   * The model the harness's configuration names, which is the one a reply without a model uses. The
   * catalogue's own default is only opencode's guess, and not what this harness is set to.
   */
  private String configuredModel(HarnessEndpoint endpoint, String providerId, JsonNode catalogue) {
    var configured = text(call(endpoint, HOME, "GET", "/global/config", null), "model");
    var prefix = providerId + "/";
    return configured != null && configured.startsWith(prefix)
        ? configured.substring(prefix.length())
        : text(catalogue.path("default"), providerId);
  }

  @Override
  public Map<String, String> mcpStatus(HarnessEndpoint endpoint) {
    var statuses = new TreeMap<String, String>();
    call(endpoint, HOME, "GET", "/mcp", null)
        .properties()
        .forEach(e -> statuses.put(e.getKey(), text(e.getValue(), "status")));
    return statuses;
  }

  /**
   * Reads the harness's global configuration first and writes only when it differs, so applying the
   * same servers twice — as every restart of the API does — leaves the harness alone.
   *
   * <p>The configuration comes back with every {@code {env:NAME}} already substituted, so header
   * values cannot be compared and are never copied back: a server is compared by URL, enabled flag
   * and header names, and one being disabled is sent only {@code enabled: false}, which opencode
   * merges into what it has. Writing back what was read would put the secret itself into the
   * harness's configuration file.
   */
  @Override
  public boolean configureMcp(HarnessEndpoint endpoint, Map<String, McpServerConfig> servers) {
    var current = call(endpoint, HOME, "GET", "/global/config", null).path("mcp");
    var patch = new TreeMap<String, Object>();
    servers.forEach(
        (name, server) -> {
          if (!matches(current.path(name), server)) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("type", "remote");
            entry.put("url", server.url());
            entry.put("headers", server.headers());
            entry.put("enabled", true);
            patch.put(name, entry);
          }
        });
    current
        .properties()
        .forEach(
            e -> {
              if (!servers.containsKey(e.getKey())
                  && e.getValue().path("enabled").asBoolean(true)) {
                patch.put(e.getKey(), Map.of("enabled", false));
              }
            });
    if (patch.isEmpty()) {
      return false;
    }
    call(endpoint, HOME, "PATCH", "/global/config", Map.of("mcp", patch));
    return true;
  }

  private static boolean matches(JsonNode current, McpServerConfig server) {
    if (current.isMissingNode()
        || !current.path("enabled").asBoolean(true)
        || !server.url().equals(text(current, "url"))) {
      return false;
    }
    var names = new TreeSet<String>();
    current.path("headers").properties().forEach(e -> names.add(e.getKey()));
    return names.equals(new TreeSet<>(server.headers().keySet()));
  }

  @Override
  public void abort(HarnessEndpoint endpoint, String directory, String sessionId) {
    call(endpoint, directory, "POST", "/session/" + sessionId + "/abort", Map.of());
  }

  /**
   * One connection per caller, read on its own virtual thread. Closing the handle closes the
   * connection, which is what ends the read; the harness ending it runs {@code onEnd} instead.
   */
  @Override
  public AutoCloseable stream(
      HarnessEndpoint endpoint,
      String directory,
      String sessionId,
      Consumer<HarnessEvent> onEvent,
      Runnable onEnd) {
    var closed = new AtomicBoolean();
    var body = new AtomicReference<InputStream>();
    var request =
        request(endpoint, directory, "/event")
            .timeout(Duration.ofDays(1))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var reader =
        Thread.ofVirtual()
            .name("harness-events-" + sessionId)
            .start(
                () -> {
                  try {
                    var response = http.send(request, BodyHandlers.ofInputStream());
                    body.set(response.body());
                    if (response.statusCode() / 100 != 2) {
                      throw new IOException("event stream answered " + response.statusCode());
                    }
                    read(response.body(), sessionId, onEvent, closed);
                  } catch (IOException e) {
                    if (!closed.get()) {
                      LOG.info("harness event stream for {} ended: {}", sessionId, e.getMessage());
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

  private void read(
      InputStream body, String sessionId, Consumer<HarnessEvent> onEvent, AtomicBoolean closed)
      throws IOException {
    try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (!closed.get() && (line = lines.readLine()) != null) {
        if (line.startsWith("data:")) {
          var event = event(json.readTree(line.substring(5)), sessionId);
          if (event != null) {
            onEvent.accept(event);
          }
        }
      }
    }
  }

  /** Null for anything about another session, or that the chat has no use for. */
  static HarnessEvent event(JsonNode event, String sessionId) {
    var type = text(event, "type");
    var properties = event.path("properties");
    if ("server.heartbeat".equals(type)) {
      return new HarnessEvent.Heartbeat();
    }
    if (type == null || !sessionId.equals(text(properties, "sessionID"))) {
      return null;
    }
    return switch (type) {
      case "message.updated" -> {
        var info = properties.path("info");
        yield new HarnessEvent.Message(text(info, "id"), text(info, "role"));
      }
      case "message.part.updated" -> {
        var part = properties.path("part");
        var mapped = part(part);
        yield mapped == null ? null : new HarnessEvent.Part(text(part, "messageID"), mapped);
      }
      case "message.part.delta" ->
          "text".equals(text(properties, "field"))
              ? new HarnessEvent.Delta(
                  text(properties, "messageID"),
                  text(properties, "partID"),
                  text(properties, "delta"))
              : null;
      case "session.status" ->
          new HarnessEvent.Status(!"idle".equals(text(properties.path("status"), "type")));
      case "session.idle" -> new HarnessEvent.Status(false);
      case "session.updated" -> {
        var title = text(properties.path("info"), "title");
        yield title == null || PLACEHOLDER_TITLE.matcher(title).matches()
            ? null
            : new HarnessEvent.Title(title);
      }
      case "session.error" -> {
        var error = properties.path("error");
        yield new HarnessEvent.Error(
            Objects.requireNonNullElse(
                text(error.path("data"), "message"),
                Objects.requireNonNullElse(text(error, "name"), "The reply failed")));
      }
      default -> null;
    };
  }

  /** Text, reasoning and tools; null for the bookkeeping parts a chat does not show. */
  static ChatPart part(JsonNode part) {
    var id = text(part, "id");
    var type = text(part, "type");
    if (type == null || part.path("synthetic").asBoolean(false)) {
      return null;
    }
    return switch (type) {
      case "text", "reasoning" -> new ChatPart(id, type, text(part, "text"), null, null);
      case "tool" -> {
        var state = part.path("state");
        yield new ChatPart(
            id, type, text(state, "title"), text(part, "tool"), text(state, "status"));
      }
      default -> null;
    };
  }

  private static Double number(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asDouble();
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private JsonNode call(
      HarnessEndpoint endpoint, String directory, String method, String path, Object body) {
    var publisher =
        body == null
            ? BodyPublishers.noBody()
            : BodyPublishers.ofString(json.writeValueAsString(body));
    var builder =
        request(endpoint, directory, path)
            .header("Content-Type", "application/json")
            .method(method, publisher);
    var response = check(send(builder));
    var content = response.body();
    return content == null || content.isBlank() ? json.nullNode() : json.readTree(content);
  }

  private HttpRequest.Builder request(HarnessEndpoint endpoint, String directory, String path) {
    var credentials = USER + ":" + endpoint.password();
    return HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
        .timeout(REQUEST_TIMEOUT)
        .header(
            "Authorization",
            "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
        .header(DIRECTORY_HEADER, directory);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return http.send(request.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new HarnessUnavailableException("the workspace's harness is not answering", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarnessUnavailableException("interrupted calling the harness", e);
    }
  }

  private static HttpResponse<String> check(HttpResponse<String> response) {
    if (response.statusCode() / 100 != 2) {
      throw new HarnessUnavailableException("the harness answered " + response.statusCode());
    }
    return response;
  }
}

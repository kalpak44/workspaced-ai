package io.github.kalpak44.workspacedai.api.session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A real HTTP client against the running server, since MockMvc cannot follow a live stream. */
final class SseClient {

  record Event(String name, JsonNode data) {
    String field(String key) {
      return data.get(key).asString();
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;

  SseClient(int port) {
    this.base = "http://localhost:" + port + "/api/v1/sessions";
  }

  String createSession() throws IOException, InterruptedException {
    var response = post("", "");
    return JSON.readTree(response.body()).get("id").asString();
  }

  HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(json))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }

  HttpResponse<Stream<String>> open(String sessionId) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(URI.create(base + "/" + sessionId + "/events"))
            .header("Accept", "text/event-stream")
            .build();
    return http.send(request, BodyHandlers.ofLines());
  }

  /** Reads one event, or empty once the server has closed the stream. */
  static Optional<Event> next(Iterator<String> lines) {
    String name = null;
    var data = new StringBuilder();
    while (lines.hasNext()) {
      var line = lines.next();
      if (line.isEmpty()) {
        if (name != null) {
          return Optional.of(new Event(name, JSON.readTree(data.toString())));
        }
      } else if (line.startsWith("event:")) {
        name = line.substring("event:".length());
      } else if (line.startsWith("data:")) {
        data.append(line.substring("data:".length()));
      }
    }
    return Optional.empty();
  }

  /** Skips ahead to the next event with that name, failing if the stream ends first. */
  static Event until(Iterator<String> lines, String name) {
    while (true) {
      var event = next(lines).orElseThrow(() -> new AssertionError("stream ended before " + name));
      if (event.name().equals(name)) {
        return event;
      }
    }
  }
}

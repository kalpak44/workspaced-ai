package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Full HTTP and browser-WebSocket path through the API to real OpenCode shells. */
@EnabledIfSystemProperty(named = "opencode.live.url", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class TerminalLiveTest {
  @LocalServerPort int port;
  @Autowired WorkspaceRepository workspaces;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();
  UUID workspace;

  @DynamicPropertySource
  static void harness(DynamicPropertyRegistry registry) {
    registry.add(
        "workspaced.workspace.local-harness.url", () -> System.getProperty("opencode.live.url"));
    registry.add(
        "workspaced.workspace.local-harness.password",
        () -> System.getProperty("opencode.live.password"));
  }

  @Test
  void createsSwitchesReconnectsAndDeletesThroughThePublicApi() throws Exception {
    workspace = workspaces.save(new WorkspaceEntity("Live terminal test", "openrouter")).getId();
    String first = null;
    String second = null;
    WebSocket socket = null;
    try {
      first = create("API live first");
      second = create("API live second");
      var output = new Output();
      socket = connect(first, output);
      socket.sendText("resize:101:31", true).join();
      send(
          socket,
          "export API_TERMINAL_CHECK=preserved; printf 'API_%s:%s\\n' 'FIRST' \"$API_TERMINAL_CHECK\"\n");
      output.await("API_FIRST:preserved");
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "switch").join();
      assertThat(output.closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(json.readTree(request("GET", "/" + first, null).body()).path("status").asString())
          .isEqualTo("running");
      var other = new Output();
      socket = connect(second, other);
      send(socket, "printf 'API_%s:%s\\n' 'SECOND' \"${API_TERMINAL_CHECK:-isolated}\"\n");
      other.await("API_SECOND:isolated");
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "switch").join();
      assertThat(other.closed.await(5, TimeUnit.SECONDS)).isTrue();
      var reconnected = new Output();
      socket = connect(first, reconnected);
      reconnected.await("API_FIRST:preserved");
      send(socket, "printf 'API_%s:%s\\n' 'RECONNECTED' \"$API_TERMINAL_CHECK\"\n");
      reconnected.await("API_RECONNECTED:preserved");
      assertThat(request("DELETE", "/" + first, null).statusCode()).isEqualTo(204);
      assertThat(reconnected.closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(request("GET", "/" + first, null).statusCode()).isEqualTo(404);
      assertThat(json.readTree(request("GET", "/" + second, null).body()).path("status").asString())
          .isEqualTo("running");
    } finally {
      if (socket != null) socket.abort();
      if (first != null) request("DELETE", "/" + first, null);
      if (second != null) request("DELETE", "/" + second, null);
      workspaces.deleteById(workspace);
    }
  }

  private String create(String title) throws Exception {
    var response = request("POST", "", json.writeValueAsString(java.util.Map.of("title", title)));
    assertThat(response.statusCode()).isEqualTo(201);
    return json.readTree(response.body()).path("id").asString();
  }

  private HttpResponse<String> request(String method, String suffix, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/workspaces/"
                        + workspace
                        + "/terminals"
                        + suffix))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build(),
        BodyHandlers.ofString());
  }

  private WebSocket connect(String id, Output output) {
    return http.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .buildAsync(
            URI.create(
                "ws://localhost:"
                    + port
                    + "/api/v1/workspaces/"
                    + workspace
                    + "/terminals/"
                    + id
                    + "/connect"),
            output)
        .join();
  }

  private static void send(WebSocket socket, String input) {
    socket.sendBinary(ByteBuffer.wrap(input.getBytes(StandardCharsets.UTF_8)), true).join();
  }

  private static final class Output implements WebSocket.Listener {
    final StringBuilder text = new StringBuilder();
    final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
      var bytes = new byte[data.remaining()];
      data.get(bytes);
      synchronized (text) {
        text.append(new String(bytes, StandardCharsets.UTF_8));
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
      closed.countDown();
      return null;
    }

    void await(String expected) throws Exception {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        synchronized (text) {
          if (text.toString().contains(expected)) return;
        }
        Thread.sleep(20);
      }
      synchronized (text) {
        assertThat(text.toString()).contains(expected);
      }
    }
  }
}

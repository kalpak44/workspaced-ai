package io.github.kalpak44.workspacedai.api.harness;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class OpenAiConnectionTest {
  HttpServer server;
  boolean exists;
  final List<String> writes = new ArrayList<>();
  HarnessEndpoint endpoint;
  final OpenCodeHarness harness = new OpenCodeHarness(JsonMapper.builder().build());

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String response = "{}";
          if (exchange.getRequestMethod().equals("GET"))
            response =
                "{\"data\":{\"connections\":"
                    + (exists ? "[{\"id\":\"workspaced-profile\",\"method\":\"oauth\"}]" : "[]")
                    + "}}";
          else
            writes.add(
                exchange.getRequestMethod()
                    + " "
                    + exchange.getRequestURI().getPath()
                    + " "
                    + body);
          var bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    endpoint =
        new HarnessEndpoint(
            URI.create("http://localhost:" + server.getAddress().getPort()), "secret");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void seedsOnceAndPreservesRotatedNativeTokensOnLaterActivations() {
    harness.activateOpenAiConnection(
        endpoint,
        "workspaced-profile",
        "ChatGPT",
        "{\"type\":\"oauth\",\"methodID\":\"chatgpt-headless\",\"refresh\":\"original-token\",\"access\":\"access\",\"expires\":1234}",
        true);
    assertThat(writes).hasSize(2);
    assertThat(writes.getFirst()).contains("POST /api/credential ", "original-token");
    exists = true;
    writes.clear();
    harness.activateOpenAiConnection(
        endpoint, "workspaced-profile", "ChatGPT", "stale snapshot", true);
    assertThat(writes).containsExactly("POST /api/credential/workspaced-profile/activate ");
    harness.removeOpenAiConnection(endpoint, "workspaced-profile");
    assertThat(writes.getLast()).startsWith("DELETE /api/credential/workspaced-profile");
  }
}

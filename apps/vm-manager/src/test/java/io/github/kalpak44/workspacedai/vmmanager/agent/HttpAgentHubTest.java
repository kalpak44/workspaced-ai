package io.github.kalpak44.workspacedai.vmmanager.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The client against a local stand-in for the agent hub's internal routes. */
class HttpAgentHubTest {

  final List<String> requests = new CopyOnWriteArrayList<>();
  volatile int status = 0;
  HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/v1/internal/", this::answer);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void answer(HttpExchange exchange) throws IOException {
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
    var post = exchange.getRequestMethod().equals("POST");
    var code = status != 0 ? status : post ? 201 : 204;
    var bytes =
        (post ? "{\"token\":\"t0k3n\",\"expiresAt\":\"2026-11-14T10:30:00Z\"}" : "")
            .getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  HttpAgentHub hub(URI url) {
    return new HttpAgentHub(
        new AgentHubProperties(
            url, URI.create("https://agent.example/api/v1"), Duration.ofSeconds(5)),
        JsonMapper.builder().build());
  }

  URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/api/v1/");
  }

  @Test
  void bootsTheVmWithItsIdTheHubAndAFreshToken() {
    var vm = UUID.randomUUID();

    var cloudConfig = hub(url()).bootstrap(vm).orElseThrow();

    assertThat(requests)
        .containsExactly("POST /api/v1/internal/enrollments {\"vmId\":\"" + vm + "\"}");
    assertThat(cloudConfig)
        .startsWith("#cloud-config\n")
        .contains("path: /etc/workspaced/agent.env")
        .contains("permissions: \"0600\"")
        .contains("WORKSPACED_VM_ID=" + vm)
        .contains("WORKSPACED_AGENT_URL=https://agent.example/api/v1")
        .contains("WORKSPACED_ENROLLMENT_TOKEN=t0k3n");
  }

  @Test
  void revokesByVmId() {
    var vm = UUID.randomUUID();

    hub(url()).revoke(vm);

    assertThat(requests).containsExactly("DELETE /api/v1/internal/agents/" + vm + " ");
  }

  @Test
  void reportsARefusal() {
    status = 409;
    var hub = hub(url());
    var vm = UUID.randomUUID();

    assertThatThrownBy(() -> hub.bootstrap(vm))
        .isInstanceOf(AgentHubException.class)
        .hasMessageContaining("409");
    status = 500;
    assertThatThrownBy(() -> hub.revoke(vm)).hasMessageContaining("500");
  }

  @Test
  void doesNothingWithoutAHub() {
    var hub = hub(null);

    assertThat(hub.bootstrap(UUID.randomUUID())).isEmpty();
    hub.revoke(UUID.randomUUID());
    assertThat(requests).isEmpty();
  }

  @Test
  void reportsAHubItCannotReach() {
    var hub = hub(URI.create("http://localhost:1/api/v1"));

    assertThatThrownBy(() -> hub.bootstrap(UUID.randomUUID()))
        .isInstanceOf(AgentHubException.class)
        .hasMessageContaining("could not reach");
  }
}

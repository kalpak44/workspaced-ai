package io.github.kalpak44.workspacedai.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a local server answering as the provider would, so no test calls the real one. */
class HttpApiKeyCheckTest {

  HttpServer server;
  final AtomicReference<String> authorization = new AtomicReference<>();
  final HttpApiKeyCheck check = new HttpApiKeyCheck();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          var status = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void acceptsAKeyTheProviderAnswersWithSuccess() throws Exception {
    assertThat(check.accepts(url(200), "sk-or-1")).isTrue();
    assertThat(authorization.get()).isEqualTo("Bearer sk-or-1");
  }

  @Test
  void rejectsAKeyTheProviderRefuses() throws Exception {
    assertThat(check.accepts(url(401), "sk-bad")).isFalse();
    assertThat(check.accepts(url(403), "sk-bad")).isFalse();
  }

  @Test
  void neverReadsAProviderFailureAsARefusal() {
    assertThatThrownBy(() -> check.accepts(url(500), "sk-or-1"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> check.accepts(url(429), "sk-or-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void pointsAtTheProvidersKeyEndpoint() {
    assertThat(ModelProvider.OPENROUTER.keyCheckUrl())
        .isEqualTo("https://openrouter.ai/api/v1/key");
    assertThat(ModelProvider.byId("openrouter")).hasValue(ModelProvider.OPENROUTER);
    assertThat(ModelProvider.byId("nope")).isEmpty();
  }

  private URI url(int status) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/" + status);
  }
}

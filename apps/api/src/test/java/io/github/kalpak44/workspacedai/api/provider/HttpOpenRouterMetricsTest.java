package io.github.kalpak44.workspacedai.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.kalpak44.workspacedai.api.workspace.ApiKeyRejectedException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Exercises the real HTTP client and JSON parsing without using a live user's provider key. */
class HttpOpenRouterMetricsTest {
  HttpServer server;
  final AtomicReference<String> authorization = new AtomicReference<>();
  final HttpOpenRouterMetrics client = new HttpOpenRouterMetrics();
  int status = 200;
  String body =
      """
      {"data":{"usage":2.5,"usage_daily":0.5,"usage_weekly":1.5,"usage_monthly":2.5,
       "limit":10,"limit_remaining":7.5,"limit_reset":"monthly","expires_at":null,
       "byok_usage":0,"include_byok_in_limit":false,"is_free_tier":false,
       "free_model_daily_requests":{"limit":50,"remaining":40},
       "label":"DO-NOT-RETURN","creator_user_id":"DO-NOT-RETURN"}}
      """;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/key",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  URI url() {
    return URI.create("http://localhost:" + server.getAddress().getPort() + "/key");
  }

  @Test
  void authenticatesWithTheSavedKeyAndReadsOnlyMetrics() {
    var result = client.read(url(), "private-key");
    assertThat(authorization.get()).isEqualTo("Bearer private-key");
    assertThat(result.usage()).isEqualByComparingTo("2.5");
    assertThat(result.limitRemaining()).isEqualByComparingTo("7.5");
    assertThat(result.freeRequestsRemaining()).isEqualTo(40);
    assertThat(result.toString()).doesNotContain("private-key", "DO-NOT-RETURN");
  }

  @Test
  void preservesNullAndMissingMetricsInsteadOfInventingZeroUsage() {
    body = "{\"data\":{\"limit\":null,\"usage\":0}}";
    var result = client.read(url(), "key");
    assertThat(result.usage()).isZero();
    assertThat(result.limit()).isNull();
    assertThat(result.usageDaily()).isNull();
    assertThat(result.freeRequestsRemaining()).isNull();
  }

  @Test
  void distinguishesRejectedKeysAndRateLimitingFromProviderFailures() {
    status = 401;
    assertThatThrownBy(() -> client.read(url(), "key")).isInstanceOf(ApiKeyRejectedException.class);
    status = 429;
    assertThatThrownBy(() -> client.read(url(), "key"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
    status = 500;
    body = "private-key";
    assertThatThrownBy(() -> client.read(url(), "key"))
        .isInstanceOf(ProviderUnreachableException.class)
        .hasMessageNotContaining("private-key");
  }

  @Test
  void sanitizesMalformedProviderResponses() {
    body = "not json private-key";
    assertThatThrownBy(() -> client.read(url(), "key"))
        .isInstanceOf(ProviderUnreachableException.class)
        .hasMessageNotContaining("private-key");
    body = "{}";
    assertThatThrownBy(() -> client.read(url(), "key"))
        .isInstanceOf(ProviderUnreachableException.class);
  }
}

package io.github.kalpak44.workspacedai.api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.kalpak44.workspacedai.api.provider.OpenRouterPricing.Price;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the real HTTP client and JSON parsing against a local stand-in for OpenRouter. */
class HttpOpenRouterPricingTest {
  HttpServer server;
  final AtomicInteger reads = new AtomicInteger();
  int status = 200;
  String body =
      """
      {"data":[
        {"id":"anthropic/claude-haiku-5.5","pricing":{"prompt":"0.000001","completion":"0.000005"}},
        {"id":"openrouter/auto","pricing":{"prompt":"-1","completion":"-1"}},
        {"id":"broken/model","pricing":{"prompt":"n/a"}}
      ]}
      """;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/models",
        exchange -> {
          reads.incrementAndGet();
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

  HttpOpenRouterPricing client(MutableClock clock) {
    var url = URI.create("http://localhost:" + server.getAddress().getPort() + "/models");
    return new HttpOpenRouterPricing(url, clock);
  }

  @Test
  void readsPricesPerMillionTokensAndSkipsUnpricedModels() {
    var prices = client(new MutableClock()).prices();
    assertThat(prices.get("anthropic/claude-haiku-5.5").inputCost()).isCloseTo(1, within());
    assertThat(prices.get("anthropic/claude-haiku-5.5").outputCost()).isCloseTo(5, within());
    assertThat(prices).containsKey("openrouter/auto").doesNotContainKey("broken/model");
  }

  @Test
  void reusesAListForAnHour() {
    var clock = new MutableClock();
    var client = client(clock);
    client.prices();
    clock.advance(Duration.ofMinutes(59));
    client.prices();
    assertThat(reads).hasValue(1);
    clock.advance(Duration.ofMinutes(2));
    client.prices();
    assertThat(reads).hasValue(2);
  }

  @Test
  void keepsTheLastListWhenOpenRouterFailsAndIsEmptyWithoutOne() {
    var clock = new MutableClock();
    status = 503;
    var client = client(clock);
    assertThat(client.prices()).isEmpty();
    status = 200;
    assertThat(client.prices()).containsKey("anthropic/claude-haiku-5.5");
    clock.advance(Duration.ofHours(2));
    status = 503;
    assertThat(client.prices().get("anthropic/claude-haiku-5.5")).isInstanceOf(Price.class);
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(1e-9);
  }

  static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-22T00:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}

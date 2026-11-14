package io.github.kalpak44.workspacedai.agenthub.caller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kalpak44.workspacedai.agenthub.MutableClock;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The verifier against a local stand-in for the API server's TokenReview endpoint. */
class TokenReviewVerifierTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String API = "system:serviceaccount:workspaced-ai:workspaced-ai-api";

  @TempDir Path dir;
  final List<JsonNode> reviews = new CopyOnWriteArrayList<>();
  final List<String> bearers = new CopyOnWriteArrayList<>();
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  volatile int status = 201;
  volatile String answer;
  HttpServer server;

  @BeforeEach
  void start() throws IOException {
    Files.writeString(dir.resolve("token"), "hub-own-token\n");
    accept(API, "workspaced-ai-agent-hub");
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/apis/authentication.k8s.io/v1/tokenreviews", this::review);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void review(HttpExchange exchange) throws IOException {
    reviews.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
    bearers.add(exchange.getRequestHeaders().getFirst("Authorization"));
    var bytes = answer.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void accept(String username, String audience) {
    answer =
        """
        {"status":{"authenticated":true,"user":{"username":"%s"},"audiences":["%s"]}}
        """
            .formatted(username, audience);
  }

  private CallerProperties properties() {
    return new CallerProperties(
        "workspaced-ai-agent-hub", API, "gateway", "vm-manager", Duration.ofSeconds(60), dir);
  }

  private TokenReviewVerifier verifier() {
    return new TokenReviewVerifier(
        properties(),
        URI.create("http://localhost:" + server.getAddress().getPort()),
        HttpClient.newHttpClient(),
        JSON,
        clock);
  }

  @Test
  void asksForTheHubsAudienceWithItsOwnToken() {
    assertThat(verifier().verify("caller-token")).hasValue(API);
    var spec = reviews.getFirst().path("spec");
    assertThat(spec.path("token").asString()).isEqualTo("caller-token");
    assertThat(spec.path("audiences").get(0).asString()).isEqualTo("workspaced-ai-agent-hub");
    assertThat(bearers).containsExactly("Bearer hub-own-token");
  }

  @Test
  void trustsAnAcceptedTokenForAMinuteThenAsksAgain() {
    var verifier = verifier();
    verifier.verify("caller-token");
    verifier.verify("caller-token");
    assertThat(reviews).hasSize(1);
    clock.advance(Duration.ofSeconds(61));
    verifier.verify("caller-token");
    assertThat(reviews).hasSize(2);
  }

  @Test
  void refusesATokenTheServerRejectsOrIssuedForAnotherAudience() {
    answer = "{\"status\":{\"authenticated\":false}}";
    assertThat(verifier().verify("bad")).isEmpty();
    accept(API, "https://kubernetes.default.svc");
    assertThat(verifier().verify("for-the-api-server")).isEmpty();
    // A refusal is not remembered: the next review may accept a token that rotated in.
    accept(API, "workspaced-ai-agent-hub");
    var verifier = verifier();
    answer = "{\"status\":{\"authenticated\":false}}";
    verifier.verify("t");
    accept(API, "workspaced-ai-agent-hub");
    assertThat(verifier.verify("t")).hasValue(API);
  }

  @Test
  void refusesWhenTheServerCannotBeAskedOrSaysNo() throws IOException {
    status = 403;
    assertThat(verifier().verify("t")).isEmpty();
    status = 201;
    Files.delete(dir.resolve("token"));
    assertThat(verifier().verify("t")).isEmpty();
    server.stop(0);
    assertThat(verifier().verify("t")).isEmpty();
  }

  @Test
  void refusesEverythingOutsideACluster() {
    var configuration = new CallerConfiguration();
    var outside = configuration.callerVerifier(properties(), new MockEnvironment(), JSON, clock);
    assertThat(outside.verify("anything")).isEmpty();

    // Inside one but without its CA, it cannot trust the API server either.
    var noCa =
        configuration.callerVerifier(
            properties(),
            new MockEnvironment().withProperty("KUBERNETES_SERVICE_HOST", "10.43.0.1"),
            JSON,
            clock);
    assertThat(noCa.verify("anything")).isEmpty();
  }
}

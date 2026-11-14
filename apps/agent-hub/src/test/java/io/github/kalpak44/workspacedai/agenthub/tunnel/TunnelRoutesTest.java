package io.github.kalpak44.workspacedai.agenthub.tunnel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.agenthub.TestDatabase;
import io.github.kalpak44.workspacedai.agenthub.agent.EnrollmentService;
import io.github.kalpak44.workspacedai.agenthub.agent.TestAgent;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tunnel end to end on a random port: supervisors' workers and the API's calls are both played
 * by the JDK's own HTTP and WebSocket clients, with real Ed25519 keys signing the handshakes.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"workspaced.agent-hub.worker-wait=300ms", "workspaced.agent-hub.max-workers=3"})
@Import(TestDatabase.class)
class TunnelRoutesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final byte TEXT = 0x01;
  private static final byte BINARY = 0x02;

  @LocalServerPort int port;
  @Autowired EnrollmentService enrollments;
  @Autowired WorkerRegistry registry;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void acceptsASignedWorkerAndCountsItAsSeen() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);

    worker(vm, agent);

    assertThat(
            jdbc.sql("select last_seen_at from agent where vm_id = ?")
                .param(vm)
                .query(Timestamp.class)
                .single())
        .isNotNull();
  }

  @Test
  void refusesAnUnprovenWorkerBeforeTheUpgrade() throws Exception {
    var vm = UUID.randomUUID();
    enrolled(vm);

    assertThat(handshakeStatus(TestSocket.connect(http, tunnel(), Map.of()))).isEqualTo(401);
    assertThat(handshakeStatus(TestSocket.connect(http, tunnel(), signed(vm, new TestAgent()))))
        .isEqualTo(401);
    assertThat(registry.idle(vm)).isZero();
  }

  @Test
  void closesAWorkerBeyondTheVmsLimit() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    for (var i = 0; i < 3; i++) {
      worker(vm, agent);
    }

    var extra = TestSocket.connect(http, tunnel(), signed(vm, agent)).get(5, TimeUnit.SECONDS);

    assertThat(extra.nextClose().code()).isEqualTo(1008);
    assertThat(registry.idle(vm)).isEqualTo(3);
  }

  @Test
  void webTrafficUsesADistinctExchangeAndRetainsVisitorAuthorization() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));
    var answer =
        http.sendAsync(
            HttpRequest.newBuilder(uri("http", "/internal/vms/" + vm + "/web/app?q=%2F"))
                .header("Authorization", "Bearer visitor")
                .GET()
                .build(),
            BodyHandlers.ofString());
    var head = worker.nextJson();
    assertThat(head.path("type").asString()).isEqualTo("web-http");
    assertThat(head.path("path").asString()).isEqualTo("/app?q=%2F");
    assertThat(head.path("headers").path("Authorization").get(0).asString())
        .isEqualTo("Bearer visitor");
    assertThat(head.path("headers").path("Host").get(0).asString()).startsWith("localhost:");
    worker.nextJson();
    worker.send("{\"type\":\"response\",\"status\":200,\"headers\":{}}");
    worker.send("site".getBytes(StandardCharsets.UTF_8));
    worker.send("{\"type\":\"end\"}");
    assertThat(answer.get(5, TimeUnit.SECONDS).body()).isEqualTo("site");
  }

  @Test
  void carriesAnHttpCallWithItsHeadersAndBodiesBothWays() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));

    var answer =
        http.sendAsync(
            HttpRequest.newBuilder(harness(vm, "/api/session?x=1&y=%2F"))
                .header("Authorization", "Basic YXBpOnNlY3JldA==")
                .header("Content-Type", "application/json")
                .header("X-Custom", "kept")
                .POST(BodyPublishers.ofString("{\"title\":\"hi\"}"))
                .build(),
            BodyHandlers.ofString());

    var head = worker.nextJson();
    assertThat(head.path("type").asString()).isEqualTo("http");
    assertThat(head.path("method").asString()).isEqualTo("POST");
    assertThat(head.path("path").asString()).isEqualTo("/api/session?x=1&y=%2F");
    var headers = head.path("headers");
    assertThat(headers.path("Content-Type").get(0).asString()).isEqualTo("application/json");
    assertThat(headers.path("X-Custom").get(0).asString()).isEqualTo("kept");
    assertThat(headers.propertyNames())
        .noneMatch(name -> name.equalsIgnoreCase("Authorization"))
        .noneMatch(name -> name.equalsIgnoreCase("Host"))
        .noneMatch(name -> name.equalsIgnoreCase("Connection"))
        .noneMatch(name -> name.equalsIgnoreCase("Upgrade"));
    assertThat(new String(worker.nextBytes(), StandardCharsets.UTF_8))
        .isEqualTo("{\"title\":\"hi\"}");
    assertThat(worker.nextJson().path("type").asString()).isEqualTo("end");

    worker.send(
        """
        {"type":"response","status":201,"headers":{"Content-Type":["application/json"],
         "X-Harness":["a","b"],"Keep-Alive":["timeout=5"],"Proxy-Thing":["x"]}}""");
    worker.send("{\"id\":".getBytes(StandardCharsets.UTF_8));
    worker.send("\"ses_1\"}".getBytes(StandardCharsets.UTF_8));
    worker.send("{\"type\":\"end\"}");

    var response = answer.get(5, TimeUnit.SECONDS);
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).isEqualTo("{\"id\":\"ses_1\"}");
    assertThat(response.headers().allValues("X-Harness")).containsExactly("a", "b");
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(response.headers().map().keySet())
        .noneMatch(name -> name.equalsIgnoreCase("Keep-Alive"))
        .noneMatch(name -> name.equalsIgnoreCase("Proxy-Thing"));
    // One exchange per worker: the hub closes it once the answer is through.
    assertThat(worker.nextClose().code()).isEqualTo(1000);
  }

  @Test
  void passesAStreamedAnswerOnFrameByFrame() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));
    var answer =
        http.sendAsync(
            HttpRequest.newBuilder(harness(vm, "/api/event")).GET().build(),
            BodyHandlers.ofInputStream());
    assertThat(worker.nextJson().path("path").asString()).isEqualTo("/api/event");
    assertThat(worker.nextJson().path("type").asString()).isEqualTo("end");

    worker.send(
        "{\"type\":\"response\",\"status\":200,"
            + "\"headers\":{\"Content-Type\":[\"text/event-stream\"]}}");
    worker.send("data: one\n\n".getBytes(StandardCharsets.UTF_8));
    var response = answer.get(5, TimeUnit.SECONDS);
    assertThat(response.statusCode()).isEqualTo(200);
    try (var body = response.body()) {
      // The second frame is not sent until the first has arrived, so nothing can be buffered.
      assertThat(read(body, 11)).isEqualTo("data: one\n\n");
      worker.send("data: two\n\n".getBytes(StandardCharsets.UTF_8));
      assertThat(read(body, 11)).isEqualTo("data: two\n\n");
    }

    // The API closing its end lets the worker go at the next frame the harness sends after.
    var deadline = Instant.now().plusSeconds(5);
    TestSocket.Closed closed = null;
    while (closed == null && Instant.now().isBefore(deadline)) {
      try {
        worker.send("data: more\n\n".getBytes(StandardCharsets.UTF_8));
      } catch (CompletionException e) {
        // The hub closed it while this was being sent.
      }
      closed = worker.closedWithin(Duration.ofMillis(50));
    }
    assertThat(closed).isNotNull();
  }

  @Test
  void answersUnavailableWhenNoWorkerComesFree() throws Exception {
    var vm = UUID.randomUUID();
    enrolled(vm);

    var started = System.nanoTime();
    var response = send(HttpRequest.newBuilder(harness(vm, "/api/info")).GET());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(JSON.readTree(response.body()).path("detail").asString())
        .isEqualTo("the workspace's harness is not connected");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(250));
  }

  @Test
  void waitsForAWorkerThatConnectsMeanwhile() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    var answer =
        http.sendAsync(
            HttpRequest.newBuilder(harness(vm, "/api/info")).GET().build(),
            BodyHandlers.ofString());

    // Connected directly: the waiting call takes it at once, so it is never counted idle.
    var worker = TestSocket.connect(http, tunnel(), signed(vm, agent)).get(5, TimeUnit.SECONDS);
    worker.nextJson();
    worker.nextJson();
    worker.send("{\"type\":\"response\",\"status\":204,\"headers\":{}}");
    worker.send("{\"type\":\"end\"}");

    assertThat(answer.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
  }

  @Test
  void answersTheSupervisorsErrorOrADroppedWorkerAsAProblem() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);

    var refused = worker(vm, agent);
    var answer =
        http.sendAsync(
            HttpRequest.newBuilder(harness(vm, "/api/info")).GET().build(),
            BodyHandlers.ofString());
    refused.nextJson();
    refused.nextJson();
    refused.send("{\"type\":\"error\",\"status\":502,\"message\":\"connection refused\"}");
    var response = answer.get(5, TimeUnit.SECONDS);
    assertThat(response.statusCode()).isEqualTo(502);
    assertThat(JSON.readTree(response.body()).path("detail").asString())
        .isEqualTo("connection refused");

    var dropped = worker(vm, agent);
    answer =
        http.sendAsync(
            HttpRequest.newBuilder(harness(vm, "/api/info")).GET().build(),
            BodyHandlers.ofString());
    dropped.nextJson();
    dropped.nextJson();
    dropped.socket.sendClose(1001, "going").join();
    assertThat(answer.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(502);
  }

  @Test
  void relaysASocketBothWaysAndClosesItFromTheApi() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));

    var opening =
        TestSocket.connect(
            http, socket(vm, "/api/pty/pty_1/connect?ticket=t"), Map.of(), "opencode", "other");
    var head = worker.nextJson();
    assertThat(head.path("type").asString()).isEqualTo("ws");
    assertThat(head.path("path").asString()).isEqualTo("/api/pty/pty_1/connect?ticket=t");
    assertThat(head.path("headers").path("Sec-WebSocket-Protocol").get(0).asString())
        .isEqualTo("opencode, other");
    assertThat(head.path("headers").propertyNames())
        .noneMatch(name -> name.equalsIgnoreCase("Sec-WebSocket-Key"))
        .noneMatch(name -> name.equalsIgnoreCase("Upgrade"));
    worker.send("{\"type\":\"open\",\"protocol\":\"opencode\"}");
    // Sent between open and the upgrade completing, so it waits for the API's socket.
    worker.relay("welcome");

    var api = opening.get(5, TimeUnit.SECONDS);
    assertThat(api.socket.getSubprotocol()).isEqualTo("opencode");
    assertThat(api.next()).isEqualTo("welcome");

    api.socket.sendText("ls\r", true).join();
    assertThat(worker.nextBytes()).isEqualTo(framed(TEXT, "ls\r".getBytes(StandardCharsets.UTF_8)));
    api.send(new byte[] {7, 8, 9});
    assertThat(worker.nextBytes()).isEqualTo(new byte[] {BINARY, 7, 8, 9});
    worker.relay(BINARY, new byte[] {4, 5});
    assertThat(api.nextBytes()).isEqualTo(new byte[] {4, 5});

    api.socket.sendClose(1000, "done").join();
    var end = worker.nextJson();
    assertThat(end.path("type").asString()).isEqualTo("end");
    assertThat(end.path("code").asInt()).isEqualTo(1000);
    assertThat(end.path("reason").asString()).isEqualTo("done");
    assertThat(worker.nextClose().code()).isEqualTo(1000);
  }

  @Test
  void closesTheApisSocketWhenTheHarnessEndsIt() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));
    var opening = TestSocket.connect(http, socket(vm, "/api/pty/pty_2/connect"), Map.of());
    worker.nextJson();
    worker.send("{\"type\":\"open\",\"protocol\":\"\"}");
    var api = opening.get(5, TimeUnit.SECONDS);
    assertThat(api.socket.getSubprotocol()).isEmpty();

    worker.send("{\"type\":\"end\",\"code\":4000,\"reason\":\"exited\"}");

    assertThat(api.nextClose()).isEqualTo(new TestSocket.Closed(4000, "exited"));
  }

  @Test
  void closesTheApisSocketWhenTheWorkerDrops() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));
    var opening = TestSocket.connect(http, socket(vm, "/api/pty/pty_3/connect"), Map.of());
    worker.nextJson();
    worker.send("{\"type\":\"open\",\"protocol\":\"\"}");
    var api = opening.get(5, TimeUnit.SECONDS);

    worker.socket.sendClose(1001, "going").join();

    assertThat(api.nextClose().code()).isEqualTo(1011);
  }

  @Test
  void refusesTheUpgradeWhenTheHarnessSocketCannotOpen() throws Exception {
    var vm = UUID.randomUUID();
    var worker = worker(vm, enrolled(vm));
    var opening = TestSocket.connect(http, socket(vm, "/api/pty/pty_4/connect"), Map.of());
    worker.nextJson();

    worker.send("{\"type\":\"error\",\"status\":502,\"message\":\"no such terminal\"}");

    assertThat(handshakeStatus(opening)).isEqualTo(502);
    assertThat(worker.nextClose().code()).isEqualTo(1000);
    assertThat(handshakeStatus(TestSocket.connect(http, socket(vm, "/api/pty"), Map.of())))
        .isEqualTo(503);
  }

  @Test
  void refusesTheHarnessRoutesThroughTheIngress() throws Exception {
    var vm = UUID.randomUUID();
    worker(vm, enrolled(vm));
    var forwarded = Map.of("X-Forwarded-For", "203.0.113.7");

    var call = HttpRequest.newBuilder(harness(vm, "/api/info")).GET();
    forwarded.forEach(call::header);
    var response = send(call);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(handshakeStatus(TestSocket.connect(http, socket(vm, "/api/pty"), forwarded)))
        .isEqualTo(404);
    // Neither took the worker.
    assertThat(registry.idle(vm)).isEqualTo(1);
  }

  @Test
  void answersNotFoundForAPathThatNamesNoVm() throws Exception {
    assertThat(
            send(HttpRequest.newBuilder(uri("http", "/internal/vms/nope/harness/api/info")).GET())
                .statusCode())
        .isEqualTo(404);
  }

  private TestAgent enrolled(UUID vm) {
    var agent = new TestAgent();
    enrollments.enroll(vm, enrollments.issue(vm).token(), agent.publicKey());
    return agent;
  }

  /** A worker the hub has registered, so a call made next finds it. */
  private TestSocket worker(UUID vm, TestAgent agent) throws Exception {
    var before = registry.idle(vm);
    var worker = TestSocket.connect(http, tunnel(), signed(vm, agent)).get(5, TimeUnit.SECONDS);
    var deadline = Instant.now().plusSeconds(5);
    while (registry.idle(vm) == before && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertThat(registry.idle(vm)).isEqualTo(before + 1);
    return worker;
  }

  private static Map<String, String> signed(UUID vm, TestAgent agent) {
    var timestamp = Instant.now().getEpochSecond();
    var headers = new HashMap<String, String>();
    headers.put("X-Agent-Vm", vm.toString());
    headers.put("X-Agent-Timestamp", Long.toString(timestamp));
    headers.put(
        "X-Agent-Signature", agent.sign("GET", "/api/v1/agent/tunnel", timestamp, new byte[0]));
    return headers;
  }

  private static int handshakeStatus(CompletableFuture<TestSocket> opening) throws Exception {
    try {
      opening.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      var cause =
          e.getCause() instanceof CompletionException wrapped ? wrapped.getCause() : e.getCause();
      assertThat(cause).isInstanceOf(WebSocketHandshakeException.class);
      return ((WebSocketHandshakeException) cause).getResponse().statusCode();
    }
    throw new AssertionError("the handshake was accepted");
  }

  private static byte[] framed(byte type, byte[] bytes) {
    var framed = new byte[bytes.length + 1];
    framed[0] = type;
    System.arraycopy(bytes, 0, framed, 1, bytes.length);
    return framed;
  }

  private static String read(InputStream body, int length) throws Exception {
    return new String(body.readNBytes(length), StandardCharsets.UTF_8);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.build(), BodyHandlers.ofString());
  }

  private URI tunnel() {
    return uri("ws", "/agent/tunnel");
  }

  private URI harness(UUID vm, String path) {
    return uri("http", "/internal/vms/" + vm + "/harness" + path);
  }

  private URI socket(UUID vm, String path) {
    return uri("ws", "/internal/vms/" + vm + "/harness" + path);
  }

  private URI uri(String scheme, String path) {
    return URI.create(scheme + "://localhost:" + port + "/api/v1" + path);
  }
}

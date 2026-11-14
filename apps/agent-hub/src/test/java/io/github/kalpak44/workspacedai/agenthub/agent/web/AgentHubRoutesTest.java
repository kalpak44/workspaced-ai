package io.github.kalpak44.workspacedai.agenthub.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.agenthub.MutableClock;
import io.github.kalpak44.workspacedai.agenthub.TestCallers;
import io.github.kalpak44.workspacedai.agenthub.TestDatabase;
import io.github.kalpak44.workspacedai.agenthub.agent.TestAgent;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.FakeVmManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end on a random port, against a real PostgreSQL, with the VM manager answered by a local
 * fake and a clock moved by hand.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestCallers.class, AgentHubRoutesTest.Clocks.class})
class AgentHubRoutesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeVmManager VM_MANAGER = new FakeVmManager();
  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));

  @TestConfiguration(proxyBeanMethods = false)
  static class Clocks {

    @Bean
    @Primary
    MutableClock testClock() {
      return CLOCK;
    }
  }

  @DynamicPropertySource
  static void vmManager(DynamicPropertyRegistry registry) {
    registry.add("workspaced.agent-hub.vm-manager-url", VM_MANAGER::url);
  }

  @AfterAll
  static void stop() {
    VM_MANAGER.close();
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void enrollsWithATokenOnceAndKeepsOnlyItsHash() throws Exception {
    var vm = UUID.randomUUID();
    var issued = issue(vm);
    assertThat(issued.statusCode()).isEqualTo(201);
    var body = JSON.readTree(issued.body());
    assertThat(body.path("vmId").asString()).isEqualTo(vm.toString());
    assertThat(Instant.parse(body.path("expiresAt").asString()))
        .isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(30)));
    var token = body.path("token").asString();
    assertThat(
            jdbc.sql("select token_hash from enrollment_token where vm_id = ?")
                .param(vm)
                .query(byte[].class)
                .single())
        .isEqualTo(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));

    var agent = new TestAgent();
    assertThat(enroll(vm, token, agent.publicKey()).statusCode()).isEqualTo(204);
    assertThat(
            jdbc.sql("select public_key from agent where vm_id = ?")
                .param(vm)
                .query(byte[].class)
                .single())
        .isEqualTo(agent.rawPublicKey());
    assertThat(enroll(vm, token, agent.publicKey()).statusCode()).isEqualTo(409);
    assertThat(issue(vm).statusCode()).isEqualTo(409);

    // Revoked, the VM has neither its agent nor a token, so the used one stays refused.
    assertThat(send("DELETE", "/internal/agents/" + vm, null, Map.of()).statusCode())
        .isEqualTo(204);
    var reused = enroll(vm, token, agent.publicKey());
    assertThat(reused.statusCode()).isEqualTo(401);
    assertThat(reused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
  }

  @Test
  void refusesAnExpiredReplacedOrForeignToken() throws Exception {
    var vm = UUID.randomUUID();
    var replaced = token(vm);
    var token = token(vm);
    var key = new TestAgent().publicKey();
    assertThat(enroll(vm, replaced, key).statusCode()).isEqualTo(401);
    assertThat(enroll(UUID.randomUUID(), token, key).statusCode()).isEqualTo(401);
    CLOCK.advance(Duration.ofMinutes(31));
    try {
      assertThat(enroll(vm, token, key).statusCode()).isEqualTo(401);
    } finally {
      CLOCK.advance(Duration.ofMinutes(-31));
    }
    assertThat(enroll(vm, token(vm), key).statusCode()).isEqualTo(204);
  }

  @Test
  void rejectsAMalformedEnrollment() throws Exception {
    var vm = UUID.randomUUID();
    var token = token(vm);
    assertThat(enroll(vm, token, "AAAA").statusCode()).isEqualTo(400);
    assertThat(enroll(vm, token, "not base64!").statusCode()).isEqualTo(400);
    assertThat(send("POST", "/agent/enroll", "{\"vmId\":\"x\"}", Map.of()).statusCode())
        .isEqualTo(400);
    assertThat(send("POST", "/agent/enroll", "{", Map.of()).statusCode()).isEqualTo(400);
    assertThat(send("POST", "/internal/enrollments", "{}", Map.of()).statusCode()).isEqualTo(400);
    assertThat(enroll(vm, token, new TestAgent().publicKey()).statusCode()).isEqualTo(204);
  }

  @Test
  void passesSignedReportsOnToTheVmManager() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    assertThat(signed(vm, agent, "/agent/ready", now()).statusCode()).isEqualTo(204);
    var heartbeat = signed(vm, agent, "/agent/heartbeat", now());
    assertThat(heartbeat.statusCode()).isEqualTo(200);
    assertThat(heartbeat.body()).isEqualTo("{\"status\":\"READY\"}");
    assertThat(VM_MANAGER.reports).contains("ready " + vm, "heartbeat " + vm);
    assertThat(
            jdbc.sql("select last_seen_at from agent where vm_id = ?")
                .param(vm)
                .query(Timestamp.class)
                .single()
                .toInstant())
        .isEqualTo(CLOCK.instant());
  }

  @Test
  void handsAProvenVmItsHarnessUnchangedAndUncached() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);

    var harness = signed(vm, agent, "/agent/harness", now());

    assertThat(harness.statusCode()).isEqualTo(200);
    assertThat(harness.body()).isEqualTo(FakeVmManager.HARNESS);
    assertThat(harness.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(harness.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(signed(vm, new TestAgent(), "/agent/harness", now()).statusCode()).isEqualTo(401);
  }

  @Test
  void answersUnavailableWhenTheVmManagerHasNoHarness() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    VM_MANAGER.status = 503;
    try {
      var harness = signed(vm, agent, "/agent/harness", now());
      assertThat(harness.statusCode()).isEqualTo(503);
      assertThat(harness.headers().firstValue("Content-Type")).hasValue("application/problem+json");
      VM_MANAGER.status = 500;
      assertThat(signed(vm, agent, "/agent/harness", now()).statusCode()).isEqualTo(502);
    } finally {
      VM_MANAGER.status = 0;
    }
  }

  @Test
  void refusesAnUnprovenRequest() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    // Signed by another key, signed too long ago, from a VM with no agent, and not signed at all.
    assertThat(signed(vm, new TestAgent(), "/agent/ready", now()).statusCode()).isEqualTo(401);
    assertThat(signed(vm, agent, "/agent/ready", now() - 61).statusCode()).isEqualTo(401);
    assertThat(signed(UUID.randomUUID(), agent, "/agent/ready", now()).statusCode()).isEqualTo(401);
    var unsigned = send("POST", "/agent/heartbeat", null, Map.of());
    assertThat(unsigned.statusCode()).isEqualTo(401);
    assertThat(unsigned.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(VM_MANAGER.reports).noneMatch(report -> report.endsWith(vm.toString()));
  }

  @Test
  void answersNotFoundForAVmTheVmManagerNoLongerHas() throws Exception {
    var vm = UUID.randomUUID();
    var agent = enrolled(vm);
    VM_MANAGER.gone.add(vm);
    assertThat(signed(vm, agent, "/agent/heartbeat", now()).statusCode()).isEqualTo(404);
    assertThat(signed(vm, agent, "/agent/ready", now()).statusCode()).isEqualTo(404);
    assertThat(signed(vm, agent, "/agent/harness", now()).statusCode()).isEqualTo(404);
  }

  @Test
  void issuesEnrollmentsOnlyToTheVmManager() throws Exception {
    var vm = UUID.randomUUID();
    var body = "{\"vmId\":\"" + vm + "\"}";
    var none =
        send("POST", "/internal/enrollments", body, Map.of(InternalRoutesFilter.TOKEN_HEADER, ""));
    assertThat(none.statusCode()).isEqualTo(401);
    for (var other : new String[] {TestCallers.API, TestCallers.GATEWAY}) {
      var token = Map.of(InternalRoutesFilter.TOKEN_HEADER, other);
      assertThat(send("POST", "/internal/enrollments", body, token).statusCode()).isEqualTo(403);
      assertThat(send("DELETE", "/internal/agents/" + vm, null, token).statusCode()).isEqualTo(403);
    }
    assertThat(
            jdbc.sql("select count(*) from enrollment_token where vm_id = ?")
                .param(vm)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void refusesInternalRoutesThroughTheIngress() throws Exception {
    var vm = UUID.randomUUID();
    for (var header : new String[] {"X-Forwarded-For", "X-Forwarded-Host", "X-Real-Ip"}) {
      var forwarded = Map.of(header, "203.0.113.7");
      var issued = send("POST", "/internal/enrollments", "{\"vmId\":\"" + vm + "\"}", forwarded);
      assertThat(issued.statusCode()).isEqualTo(404);
      assertThat(issued.headers().firstValue("Content-Type")).hasValue("application/problem+json");
      assertThat(send("DELETE", "/internal/agents/" + vm, null, forwarded).statusCode())
          .isEqualTo(404);
    }
    assertThat(
            jdbc.sql("select count(*) from enrollment_token where vm_id = ?")
                .param(vm)
                .query(Long.class)
                .single())
        .isZero();
    // The agent routes are meant to come through it.
    var forwarded =
        send("POST", "/agent/heartbeat", null, Map.of("X-Forwarded-For", "203.0.113.7"));
    assertThat(forwarded.statusCode()).isEqualTo(401);
  }

  @Test
  void reportsItsHealthToTheProbes() throws Exception {
    assertThat(send("GET", "/actuator/health/readiness", null, Map.of()).body()).contains("UP");
  }

  private long now() {
    return CLOCK.instant().getEpochSecond();
  }

  private TestAgent enrolled(UUID vm) throws Exception {
    var agent = new TestAgent();
    assertThat(enroll(vm, token(vm), agent.publicKey()).statusCode()).isEqualTo(204);
    return agent;
  }

  private String token(UUID vm) throws Exception {
    JsonNode body = JSON.readTree(issue(vm).body());
    return body.path("token").asString();
  }

  private HttpResponse<String> issue(UUID vm) throws Exception {
    return send("POST", "/internal/enrollments", "{\"vmId\":\"" + vm + "\"}", Map.of());
  }

  private HttpResponse<String> enroll(UUID vm, String token, String key) throws Exception {
    var body = JSON.createObjectNode();
    body.put("vmId", vm.toString());
    body.put("token", token);
    body.put("publicKey", key);
    return send("POST", "/agent/enroll", body.toString(), Map.of());
  }

  private HttpResponse<String> signed(UUID vm, TestAgent agent, String route, long timestamp)
      throws Exception {
    var signature = agent.sign("POST", "/api/v1" + route, timestamp, new byte[0]);
    return send(
        "POST",
        route,
        null,
        Map.of(
            "X-Agent-Vm", vm.toString(),
            "X-Agent-Timestamp", Long.toString(timestamp),
            "X-Agent-Signature", signature));
  }

  private HttpResponse<String> send(
      String method, String path, String body, Map<String, String> headers) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    // The VM manager's token on its routes, unless the test says otherwise.
    if (path.startsWith("/internal") && !headers.containsKey(InternalRoutesFilter.TOKEN_HEADER)) {
      request.header(InternalRoutesFilter.TOKEN_HEADER, TestCallers.VM_MANAGER);
    }
    headers.forEach(request::header);
    return http.send(request.build(), BodyHandlers.ofString());
  }
}

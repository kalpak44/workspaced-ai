package io.github.kalpak44.workspacedai.agenthub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import io.github.kalpak44.workspacedai.agenthub.MutableClock;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmManagerClient;
import io.github.kalpak44.workspacedai.agenthub.vmmanager.VmNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

  static final String READY = "/api/v1/agent/ready";
  static final String HEARTBEAT = "/api/v1/agent/heartbeat";
  static final String HARNESS_ROUTE = "/api/v1/agent/harness";
  static final byte[] HARNESS = "{\"image\":\"h\",\"environment\":{}}".getBytes();

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final InMemoryAgentStore store = new InMemoryAgentStore();
  final List<String> reports = new CopyOnWriteArrayList<>();
  final List<UUID> gone = new CopyOnWriteArrayList<>();
  final VmManagerClient vmManager =
      new VmManagerClient() {
        @Override
        public void ready(UUID vmId) {
          report("ready", vmId);
        }

        @Override
        public String heartbeat(UUID vmId) {
          report("heartbeat", vmId);
          return "RESTARTING";
        }

        @Override
        public byte[] harness(UUID vmId) {
          report("harness", vmId);
          return HARNESS;
        }

        private void report(String kind, UUID vmId) {
          if (gone.contains(vmId)) {
            throw new VmNotFoundException(vmId);
          }
          reports.add(kind + " " + vmId);
        }
      };
  final AgentHubProperties properties =
      new AgentHubProperties(
          URI.create("http://vm-manager"),
          Duration.ofMinutes(30),
          Duration.ofSeconds(60),
          Duration.ofSeconds(10),
          Duration.ofSeconds(10),
          64);
  final AgentService service = new AgentService(store, vmManager, properties, clock);
  final TestAgent agent = new TestAgent();
  final UUID vm = UUID.randomUUID();

  AgentServiceTest() {
    store.keys.put(vm, agent.rawPublicKey());
  }

  SignedRequest signed(String path, long timestamp) {
    return new SignedRequest(
        vm.toString(),
        Long.toString(timestamp),
        agent.sign("POST", path, timestamp, new byte[0]),
        "POST",
        path,
        new byte[0]);
  }

  long now() {
    return clock.instant().getEpochSecond();
  }

  @Test
  void passesSignedReportsOnAndRecordsWhenTheVmWasSeen() {
    service.ready(signed(READY, now()));
    clock.advance(Duration.ofSeconds(30));
    service.heartbeat(signed(HEARTBEAT, now()));
    assertThat(reports).containsExactly("ready " + vm, "heartbeat " + vm);
    assertThat(store.lastSeen.get(vm)).isEqualTo(clock.instant());
  }

  @Test
  void answersTheHeartbeatWithTheVmsStatus() {
    assertThat(service.heartbeat(signed(HEARTBEAT, now()))).isEqualTo("RESTARTING");
  }

  @Test
  void handsAProvenVmItsHarnessAndNoOtherOne() {
    assertThat(service.harness(signed(HARNESS_ROUTE, now()))).isSameAs(HARNESS);
    assertThat(store.lastSeen.get(vm)).isEqualTo(clock.instant());
    var heartbeat = signed(HEARTBEAT, now());
    var replayed =
        new SignedRequest(
            heartbeat.vmId(),
            heartbeat.timestamp(),
            heartbeat.signature(),
            "POST",
            HARNESS_ROUTE,
            new byte[0]);
    assertThatThrownBy(() -> service.harness(replayed))
        .isInstanceOf(SignatureRefusedException.class);
    assertThat(reports).containsExactly("harness " + vm);
  }

  @Test
  void acceptsAClockSkewOfUpToTheLimitEitherWay() {
    service.heartbeat(signed(HEARTBEAT, now() - 60));
    service.heartbeat(signed(HEARTBEAT, now() + 60));
    assertThatThrownBy(() -> service.heartbeat(signed(HEARTBEAT, now() - 61)))
        .isInstanceOf(SignatureRefusedException.class);
    assertThatThrownBy(() -> service.heartbeat(signed(HEARTBEAT, now() + 61)))
        .isInstanceOf(SignatureRefusedException.class);
  }

  @Test
  void signsTheBodyToo() {
    var body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    var ts = now();
    var request =
        new SignedRequest(
            vm.toString(),
            Long.toString(ts),
            agent.sign("POST", READY, ts, body),
            "POST",
            READY,
            body);
    assertThat(service.authenticate(request)).isEqualTo(vm);
    var tampered =
        new SignedRequest(
            request.vmId(),
            request.timestamp(),
            request.signature(),
            "POST",
            READY,
            "{\"x\":2}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> service.authenticate(tampered))
        .isInstanceOf(SignatureRefusedException.class);
  }

  @Test
  void refusesASignatureForAnotherRouteOrKey() {
    var ts = now();
    var otherRoute =
        new SignedRequest(
            vm.toString(),
            Long.toString(ts),
            signed(READY, ts).signature(),
            "POST",
            HEARTBEAT,
            new byte[0]);
    assertThatThrownBy(() -> service.heartbeat(otherRoute))
        .isInstanceOf(SignatureRefusedException.class);
    var otherKey =
        new SignedRequest(
            vm.toString(),
            Long.toString(ts),
            new TestAgent().sign("POST", READY, ts, new byte[0]),
            "POST",
            READY,
            new byte[0]);
    assertThatThrownBy(() -> service.ready(otherKey)).isInstanceOf(SignatureRefusedException.class);
    assertThat(reports).isEmpty();
  }

  @Test
  void refusesMissingOrMalformedHeadersAndUnknownVms() {
    var ts = Long.toString(now());
    var good = signed(READY, now()).signature();
    for (var request :
        List.of(
            new SignedRequest(null, ts, good, "POST", READY, new byte[0]),
            new SignedRequest(vm.toString(), null, good, "POST", READY, new byte[0]),
            new SignedRequest(vm.toString(), ts, null, "POST", READY, new byte[0]),
            new SignedRequest("not-a-uuid", ts, good, "POST", READY, new byte[0]),
            new SignedRequest(vm.toString(), "-5", good, "POST", READY, new byte[0]),
            new SignedRequest(vm.toString(), ts, "%%%", "POST", READY, new byte[0]),
            new SignedRequest(
                UUID.randomUUID().toString(), ts, good, "POST", READY, new byte[0]))) {
      assertThatThrownBy(() -> service.ready(request))
          .isInstanceOf(SignatureRefusedException.class);
    }
    assertThat(reports).isEmpty();
  }

  @Test
  void reportsAVmTheVmManagerNoLongerHas() {
    gone.add(vm);
    assertThatThrownBy(() -> service.ready(signed(READY, now())))
        .isInstanceOf(VmNotFoundException.class);
  }
}

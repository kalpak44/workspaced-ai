package io.github.kalpak44.workspacedai.agenthub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import io.github.kalpak44.workspacedai.agenthub.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnrollmentServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final InMemoryAgentStore store = new InMemoryAgentStore();
  final AgentHubProperties properties =
      new AgentHubProperties(
          URI.create("http://vm-manager"),
          Duration.ofMinutes(30),
          Duration.ofSeconds(60),
          Duration.ofSeconds(10));
  final EnrollmentService service = new EnrollmentService(store, properties, clock);
  final TestAgent agent = new TestAgent();
  final UUID vm = UUID.randomUUID();

  @Test
  void issuesARandomTokenAndKeepsOnlyItsHash() {
    var issued = service.issue(vm);
    assertThat(issued.vmId()).isEqualTo(vm);
    assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
    assertThat(Base64.getUrlDecoder().decode(issued.token())).hasSize(32);
    assertThat(issued.token()).doesNotContain("=");
    assertThat(store.tokens.get(vm).hash()).hasSize(32).isNotEqualTo(issued.token().getBytes());
    assertThat(service.issue(vm).token()).isNotEqualTo(issued.token());
  }

  @Test
  void enrollsOnceAndUsesTheTokenUp() {
    var token = service.issue(vm).token();
    service.enroll(vm, token, agent.publicKey());
    assertThat(store.keys.get(vm)).isEqualTo(agent.rawPublicKey());
    assertThat(store.tokens).doesNotContainKey(vm);
    assertThatThrownBy(() -> service.enroll(vm, token, agent.publicKey()))
        .isInstanceOf(AlreadyEnrolledException.class);
    assertThatThrownBy(() -> service.issue(vm)).isInstanceOf(AlreadyEnrolledException.class);
  }

  @Test
  void refusesAReplacedExpiredOrForeignToken() {
    var replaced = service.issue(vm).token();
    var token = service.issue(vm).token();
    assertThatThrownBy(() -> service.enroll(vm, replaced, agent.publicKey()))
        .isInstanceOf(EnrollmentRefusedException.class);
    assertThatThrownBy(() -> service.enroll(UUID.randomUUID(), token, agent.publicKey()))
        .isInstanceOf(EnrollmentRefusedException.class);
    clock.advance(Duration.ofMinutes(30));
    assertThatThrownBy(() -> service.enroll(vm, token, agent.publicKey()))
        .isInstanceOf(EnrollmentRefusedException.class);
    assertThat(store.keys).isEmpty();
  }

  @Test
  void rejectsAMalformedKeyBeforeLookingAtTheToken() {
    var token = service.issue(vm).token();
    for (var key : new String[] {"not base64!", Base64.getEncoder().encodeToString(new byte[31])}) {
      assertThatThrownBy(() -> service.enroll(vm, token, key))
          .isInstanceOf(MalformedKeyException.class);
    }
    assertThat(store.tokens).containsKey(vm);
  }

  @Test
  void revokingForgetsTheAgentAndAnyPendingToken() {
    service.enroll(vm, service.issue(vm).token(), agent.publicKey());
    var other = UUID.randomUUID();
    service.issue(other);
    service.revoke(vm);
    service.revoke(other);
    service.revoke(UUID.randomUUID());
    assertThat(store.keys).isEmpty();
    assertThat(store.tokens).isEmpty();
  }
}

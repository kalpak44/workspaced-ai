package io.github.kalpak44.workspacedai.agenthub.vmmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.agenthub.AgentHubProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpVmManagerClientTest {

  final FakeVmManager fake = new FakeVmManager();
  final UUID vm = UUID.randomUUID();

  HttpVmManagerClient client(URI url) {
    var properties =
        new AgentHubProperties(
            url,
            Duration.ofMinutes(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            64);
    return new HttpVmManagerClient(
        properties, HttpClient.newHttpClient(), JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    fake.close();
  }

  @Test
  void forwardsVersionedReadinessWithoutUsingTheLegacyRoute() {
    client(fake.url()).ready(vm, 42L);
    assertThat(fake.reports).containsExactly("ready-binding " + vm);
    assertThat(fake.bodies).containsExactly("{\"revision\":42}");
  }

  @Test
  void fallsBackToTheLegacyReadinessRouteWithoutARevision() {
    client(fake.url()).ready(vm, null);
    assertThat(fake.reports).containsExactly("ready " + vm);
    assertThat(fake.bodies).isEmpty();
  }

  @Test
  void postsBothReportsAndReadsTheStatusTheHeartbeatAnswers() {
    var client = client(URI.create(fake.url() + "/"));
    client.ready(vm);
    fake.vmStatus = "RESTARTING";
    assertThat(client.heartbeat(vm, null)).isEqualTo(new Heartbeat("RESTARTING", null));
    assertThat(fake.reports).containsExactly("ready " + vm, "heartbeat " + vm);
  }

  @Test
  void passesTheFirewallRulesOnAsTheyCame() {
    fake.firewall = "[{\"protocol\":\"tcp\",\"port\":\"443\"}]";

    var rules = client(fake.url()).heartbeat(vm, null).firewall();

    assertThat(rules.get(0).path("port").asString()).isEqualTo("443");
  }

  @Test
  void sendsTheMemoryReadingAsJson() {
    client(fake.url()).heartbeat(vm, new VmMemory(4096, 1024));
    assertThat(fake.bodies)
        .containsExactly("{\"memory\":{\"totalBytes\":4096,\"availableBytes\":1024}}");
  }

  @Test
  void passesTheHarnessOnAsItCame() {
    assertThat(new String(client(fake.url()).harness(vm), StandardCharsets.UTF_8))
        .isEqualTo(FakeVmManager.HARNESS);
    assertThat(fake.reports).containsExactly("harness " + vm);
  }

  @Test
  void refusesAHeartbeatAnswerWithoutAStatus() {
    var client = client(fake.url());
    fake.body = "{\"id\":\"x\"}";
    assertThatThrownBy(() -> client.heartbeat(vm, null))
        .isInstanceOf(VmManagerException.class)
        .hasMessageContaining("without a status");
    fake.body = "not json";
    assertThatThrownBy(() -> client.heartbeat(vm, null))
        .isInstanceOf(VmManagerException.class)
        .hasMessageContaining("not JSON");
  }

  @Test
  void tellsAVmItNoLongerHasFromAFailure() {
    var client = client(fake.url());
    fake.gone.add(vm);
    assertThatThrownBy(() -> client.heartbeat(vm, null)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> client.harness(vm)).isInstanceOf(VmNotFoundException.class);
    fake.status = 500;
    assertThatThrownBy(() -> client.ready(vm))
        .isInstanceOf(VmManagerException.class)
        .isNotInstanceOf(VmManagerException.Unavailable.class);
  }

  @Test
  void tellsAnUnavailableVmManagerFromAFailedOne() {
    fake.status = 503;
    assertThatThrownBy(() -> client(fake.url()).harness(vm))
        .isInstanceOf(VmManagerException.Unavailable.class)
        .hasMessageContaining("harness");
  }

  @Test
  void failsWhenItCannotBeReached() {
    var url = fake.url();
    fake.close();
    assertThatThrownBy(() -> client(url).ready(vm)).isInstanceOf(VmManagerException.class);
  }
}

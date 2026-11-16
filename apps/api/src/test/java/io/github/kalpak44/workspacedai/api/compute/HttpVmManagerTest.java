package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpVmManagerTest {

  final FakeVmManager fake = new FakeVmManager();
  final HttpVmManager vmManager = client(fake.url());
  final UUID workspace = UUID.randomUUID();
  final UUID user = UUID.randomUUID();

  static HttpVmManager client(URI url) {
    return new HttpVmManager(
        new VmManagerProperties(url, Duration.ofSeconds(2)), JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    fake.close();
  }

  @Test
  void readsTheSizesOnOffer() {
    var sizes = vmManager.sizes();

    assertThat(sizes).extracting(VmSize::serverType).containsExactly("cx23", "cx33", "cpx22");
    var cx23 = sizes.getFirst();
    assertThat(cx23.isDefault()).isTrue();
    assertThat(cx23.cores()).isEqualTo(2);
    assertThat(cx23.memoryGb()).isEqualTo(4.0);
    assertThat(cx23.diskGb()).isEqualTo(40);
    assertThat(cx23.location()).isEqualTo("nbg1");
    assertThat(cx23.monthlyGross()).isEqualByComparingTo(new BigDecimal("6.59"));
    assertThat(cx23.recommended()).isTrue();
    assertThat(cx23.available()).isTrue();
    assertThat(vmManager.sizes().getLast().available()).isFalse();
  }

  @Test
  void createsAVmForTheWorkspaceAndItsOwner() {
    var vm =
        vmManager.create(
            workspace,
            user,
            " cx33 ",
            "sk-or-v1-test",
            Map.of("OPENCODE_SERVER_PASSWORD", "pw", "OPENROUTER_BASE_URL", "https://or"));

    assertThat(vm.serverType()).isEqualTo("cx33");
    assertThat(fake.vms).containsKey(vm.id());
    var sent = fake.creates.getFirst();
    assertThat(sent.path("workspaceId").asString()).isEqualTo(workspace.toString());
    assertThat(sent.path("userId").asString()).isEqualTo(user.toString());
    assertThat(sent.path("serverType").asString()).isEqualTo("cx33");
    assertThat(sent.path("openRouterApiKey").asString()).isEqualTo("sk-or-v1-test");
    assertThat(sent.path("environment").path("OPENCODE_SERVER_PASSWORD").asString())
        .isEqualTo("pw");
    assertThat(sent.path("environment").path("OPENROUTER_BASE_URL").asString())
        .isEqualTo("https://or");
  }

  @Test
  void readsTheWorkspaceVmsMetrics() {
    assertThatThrownBy(() -> vmManager.metricsFor(workspace, "1h"))
        .isInstanceOf(IllegalStateException.class);
    vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    var metrics = vmManager.metricsFor(workspace, "1h");

    assertThat(metrics.cores()).isEqualTo(2);
    assertThat(metrics.timestamps()).hasSize(2);
    assertThat(metrics.series().get("cpu")).containsExactly(12.5, null);
    assertThat(metrics.memory().availableBytes()).isEqualTo(1024);
    assertThatThrownBy(() -> vmManager.metricsFor(workspace, "1y"))
        .isInstanceOf(MetricsRangeRejectedException.class)
        .hasMessageContaining("not offered");
  }

  @Test
  void addsAndRemovesSecretsOnTheWorkspacesVm() {
    var vm = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    vmManager.putSecret(workspace, "GITHUB_TOKEN", "ghp_x");
    assertThat(fake.secrets.get(vm.id())).containsEntry("GITHUB_TOKEN", "ghp_x");

    assertThatThrownBy(() -> vmManager.putSecret(workspace, "GITHUB_TOKEN", "again"))
        .isInstanceOf(VmSecretRefusedException.class)
        .hasMessageContaining("already has");

    vmManager.removeSecret(workspace, "GITHUB_TOKEN");
    vmManager.removeSecret(workspace, "GITHUB_TOKEN");
    assertThat(fake.secrets.get(vm.id())).doesNotContainKey("GITHUB_TOKEN");
  }

  @Test
  void readsAndReplacesTheFirewallOfTheWorkspacesVm() {
    var vm = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    var empty = vmManager.firewallFor(workspace);
    assertThat(empty.address()).isEqualTo("203.0.113.10");
    assertThat(empty.rules()).isEmpty();

    var rule = new FirewallRule("tcp", "8080", List.of("0.0.0.0/0"), "Blog");
    assertThat(vmManager.setFirewall(workspace, List.of(rule))).containsExactly(rule);
    assertThat(fake.firewalls.get(vm.id()).path("rules").get(0).path("port").asString())
        .isEqualTo("8080");
    assertThat(vmManager.firewallFor(workspace).rules()).containsExactly(rule);
  }

  @Test
  void passesOnTheReasonARuleIsRefused() {
    vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    assertThatThrownBy(
            () ->
                vmManager.setFirewall(
                    workspace, List.of(new FirewallRule("tcp", "0", List.of(), null))))
        .isInstanceOf(FirewallRuleRejectedException.class)
        .hasMessageContaining("ports run from 1 to 65535");
    assertThatThrownBy(() -> vmManager.firewallFor(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesASecretForAWorkspaceWithNoVmAndRemovesNothing() {
    var none = UUID.randomUUID();
    assertThatThrownBy(() -> vmManager.putSecret(none, "A", "v"))
        .isInstanceOf(IllegalStateException.class);
    vmManager.removeSecret(none, "A");
  }

  @Test
  void leavesTheSizeToTheVmManagerWhenNoneIsAsked() {
    vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());
    assertThat(fake.creates.getFirst().has("serverType")).isFalse();
  }

  @Test
  void returnsTheWorkspacesVmWhenAskedAgain() {
    var first = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());
    assertThat(vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of())).isEqualTo(first);
  }

  @Test
  void passesTheVmManagersReasonForARejectedSize() {
    assertThatThrownBy(() -> vmManager.create(workspace, user, "cx53", "sk-or-v1-test", Map.of()))
        .isInstanceOf(ServerTypeRejectedException.class)
        .hasMessageContaining("server type cx53 is not offered; choose one of cx23, cx33");
  }

  @Test
  void passesOnASizeHetznerHasSoldOut() {
    assertThatThrownBy(() -> vmManager.create(workspace, user, "cpx22", "sk-or-v1-test", Map.of()))
        .isInstanceOf(ServerTypeSoldOutException.class)
        .hasMessageContaining("sold out");
  }

  @Test
  void deletesEveryVmOfTheWorkspaceAndNothingElse() {
    vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());
    var other = vmManager.create(UUID.randomUUID(), user, null, "sk-or-v1-test", Map.of());

    vmManager.deleteFor(workspace);
    vmManager.deleteFor(workspace);

    assertThat(fake.vms).containsOnlyKeys(other.id());
  }

  @Test
  void listsEveryVmWithItsWorkspaceAndStatus() {
    var vm = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    assertThat(vmManager.vms())
        .containsExactly(new ProvisionedVm(vm.id(), workspace, "cx23", "PROVISIONING"));
  }

  @Test
  void restartsTheWorkspacesVmAndRefusesOneWithNone() {
    var vm = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());

    vmManager.restartFor(workspace);

    assertThat(fake.restarts).containsExactly(vm.id());
    var none = UUID.randomUUID();
    assertThatThrownBy(() -> vmManager.restartFor(none)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void hearsEachVmFromTheEventStream() throws Exception {
    var vm = vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of());
    var heard = new LinkedBlockingQueue<ProvisionedVm>();

    vmManager.watch(heard::add);

    assertThat(heard.poll(5, TimeUnit.SECONDS)).isEqualTo(vm);
  }

  @Test
  void watchesNothingWhenUnconfigured() {
    var heard = new ArrayList<ProvisionedVm>();

    client(null).watch(heard::add);

    assertThat(heard).isEmpty();
  }

  @Test
  void tellsAnOutageFromAFailure() {
    fake.status = 503;
    fake.detail = "the VMs at Hetzner are not loaded yet";
    assertThatThrownBy(() -> vmManager.create(workspace, user, null, "sk-or-v1-test", Map.of()))
        .isInstanceOf(VmManagerUnavailableException.class)
        .hasMessageContaining("not loaded yet");

    fake.status = 502;
    fake.detail = "Hetzner answered 500";
    assertThatThrownBy(vmManager::sizes)
        .isInstanceOf(VmManagerFailedException.class)
        .hasMessageContaining("Hetzner answered 500");
  }

  @Test
  void isUnavailableWhenUnconfiguredOrUnreachable() {
    assertThatThrownBy(() -> client(null).sizes())
        .isInstanceOf(VmManagerUnavailableException.class)
        .hasMessageContaining("no VM manager is configured");

    fake.close();
    assertThatThrownBy(vmManager::sizes)
        .isInstanceOf(VmManagerUnavailableException.class)
        .hasMessageContaining("cannot be reached");
  }
}

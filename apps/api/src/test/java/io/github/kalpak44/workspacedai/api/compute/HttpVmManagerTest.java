package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
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

    assertThat(sizes).extracting(VmSize::serverType).containsExactly("cx23", "cx33");
    var cx23 = sizes.getFirst();
    assertThat(cx23.isDefault()).isTrue();
    assertThat(cx23.cores()).isEqualTo(2);
    assertThat(cx23.memoryGb()).isEqualTo(4.0);
    assertThat(cx23.diskGb()).isEqualTo(40);
    assertThat(cx23.location()).isEqualTo("nbg1");
    assertThat(cx23.monthlyGross()).isEqualByComparingTo(new BigDecimal("6.59"));
  }

  @Test
  void createsAVmForTheWorkspaceAndItsOwner() {
    var vm = vmManager.create(workspace, user, " cx33 ");

    assertThat(vm.serverType()).isEqualTo("cx33");
    assertThat(fake.vms).containsKey(vm.id());
    var sent = fake.creates.getFirst();
    assertThat(sent.path("workspaceId").asString()).isEqualTo(workspace.toString());
    assertThat(sent.path("userId").asString()).isEqualTo(user.toString());
    assertThat(sent.path("serverType").asString()).isEqualTo("cx33");
  }

  @Test
  void leavesTheSizeToTheVmManagerWhenNoneIsAsked() {
    vmManager.create(workspace, user, null);
    assertThat(fake.creates.getFirst().has("serverType")).isFalse();
  }

  @Test
  void returnsTheWorkspacesVmWhenAskedAgain() {
    var first = vmManager.create(workspace, user, null);
    assertThat(vmManager.create(workspace, user, null)).isEqualTo(first);
  }

  @Test
  void passesTheVmManagersReasonForARejectedSize() {
    assertThatThrownBy(() -> vmManager.create(workspace, user, "cx53"))
        .isInstanceOf(ServerTypeRejectedException.class)
        .hasMessageContaining("server type cx53 is not offered; choose one of cx23, cx33");
  }

  @Test
  void deletesEveryVmOfTheWorkspaceAndNothingElse() {
    vmManager.create(workspace, user, null);
    var other = vmManager.create(UUID.randomUUID(), user, null);

    vmManager.deleteFor(workspace);
    vmManager.deleteFor(workspace);

    assertThat(fake.vms).containsOnlyKeys(other.id());
  }

  @Test
  void listsEveryVmWithItsWorkspaceAndStatus() {
    var vm = vmManager.create(workspace, user, null);

    assertThat(vmManager.vms())
        .containsExactly(new ProvisionedVm(vm.id(), workspace, "cx23", "PROVISIONING"));
  }

  @Test
  void restartsTheWorkspacesVmAndRefusesOneWithNone() {
    var vm = vmManager.create(workspace, user, null);

    vmManager.restartFor(workspace);

    assertThat(fake.restarts).containsExactly(vm.id());
    var none = UUID.randomUUID();
    assertThatThrownBy(() -> vmManager.restartFor(none)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void hearsEachVmFromTheEventStream() throws Exception {
    var vm = vmManager.create(workspace, user, null);
    var heard = new LinkedBlockingQueue<ProvisionedVm>();

    vmManager.watch(heard::add);

    assertThat(heard.poll(5, TimeUnit.SECONDS)).isEqualTo(vm);
  }

  @Test
  void watchesNothingWhenUnconfigured() {
    client(null)
        .watch(
            _ -> {
              throw new AssertionError("heard a VM with no VM manager");
            });
  }

  @Test
  void tellsAnOutageFromAFailure() {
    fake.status = 503;
    fake.detail = "the VMs at Hetzner are not loaded yet";
    assertThatThrownBy(() -> vmManager.create(workspace, user, null))
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

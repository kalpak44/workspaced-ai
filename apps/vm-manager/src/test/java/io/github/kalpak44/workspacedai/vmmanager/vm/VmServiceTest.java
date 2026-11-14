package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The service on its own: an in-memory store, Hetzner kept in memory too, a hand-moved clock, and
 * the events it publishes.
 */
class VmServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final List<Object> published = new ArrayList<>();
  final FakeHetznerClient hetzner = new FakeHetznerClient();
  final VmRepository vms = new InMemoryVmRepository();
  boolean inventoryLoaded = true;
  final VmService service =
      new VmService(
          vms,
          new VmProperties(List.of("cx23", "cx33")),
          hetzner,
          new HetznerProperties(
              "t0ken",
              URI.create("http://unused"),
              "nbg1",
              "ubuntu-24.04",
              List.of("ops"),
              "workspaced-ai-vms",
              Duration.ofSeconds(1)),
          () -> inventoryLoaded,
          published::add,
          clock);

  final UUID workspace = UUID.randomUUID();
  final UUID user = UUID.randomUUID();

  @Test
  void createsAHetznerServerLabelledWithItsWorkspaceAndUser() {
    var vm = service.create(workspace, user, null).vm();

    var spec = hetzner.created.getFirst();
    assertThat(spec.name()).isEqualTo("vm-" + vm.id());
    assertThat(spec.serverType()).isEqualTo("cx23");
    assertThat(spec.location()).isEqualTo("nbg1");
    assertThat(spec.image()).isEqualTo("ubuntu-24.04");
    assertThat(spec.sshKeys()).containsExactly("ops");
    assertThat(spec.labels())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "managed-by", "workspaced-ai-vm-manager",
                "vm-id", vm.id().toString(),
                "workspace-id", workspace.toString(),
                "user-id", user.toString()));
    assertThat(vm.userId()).isEqualTo(user);
  }

  @Test
  void keepsWhatHetznerAssignedAndPublishesIt() {
    var created = service.create(workspace, user, "cx33");

    assertThat(created.isNew()).isTrue();
    var vm = created.vm();
    var server = hetzner.servers.values().iterator().next();
    assertThat(vm.serverId()).isEqualTo(server.id());
    assertThat(vm.ipv4()).isEqualTo(server.ipv4());
    assertThat(vm.workspaceId()).isEqualTo(workspace);
    assertThat(vm.serverType()).isEqualTo("cx33");
    assertThat(vm.status()).isEqualTo(VmStatus.PROVISIONING);
    assertThat(vm.createdAt()).isEqualTo(clock.instant());
    assertThat(published).containsExactly(new VmEvent.Changed(vm));
  }

  @Test
  void givesAWorkspaceOneServerAndPublishesNothingTheSecondTime() {
    var first = service.create(workspace, user, "cx33").vm();
    published.clear();

    var again = service.create(workspace, user, "cx23");

    assertThat(again.isNew()).isFalse();
    assertThat(again.vm()).isEqualTo(first);
    assertThat(hetzner.created).hasSize(1);
    assertThat(published).isEmpty();
  }

  @Test
  void refusesATypeNotOfferedBeforeAskingHetzner() {
    assertThatThrownBy(() -> service.create(workspace, user, "cx53"))
        .isInstanceOf(ServerTypeNotOfferedException.class)
        .hasMessage("server type cx53 is not offered; choose one of cx23, cx33");
    assertThat(hetzner.created).isEmpty();
  }

  @Test
  void refusesToCreateBeforeTheInventoryIsLoaded() {
    inventoryLoaded = false;

    assertThatThrownBy(() -> service.create(workspace, user, null))
        .isInstanceOf(VmInventoryNotLoadedException.class);
    assertThat(hetzner.created).isEmpty();
  }

  @Test
  void keepsNothingWhenHetznerRefuses() {
    hetzner.failure = new HetznerException("Hetzner answered 422 on POST /servers");

    assertThatThrownBy(() -> service.create(workspace, user, null)).isSameAs(hetzner.failure);
    assertThat(vms.findAll()).isEmpty();
    assertThat(published).isEmpty();
  }

  @Test
  void listsOldestFirstAndByWorkspace() {
    var a = service.create(UUID.randomUUID(), user, null).vm();
    clock.advance(Duration.ofSeconds(1));
    var b = service.create(workspace, user, null).vm();

    assertThat(service.list(Optional.empty())).containsExactly(a, b);
    assertThat(service.list(Optional.of(workspace))).containsExactly(b);
  }

  @Test
  void followsReadyRestartAndHeartbeatOnTheClock() {
    var vm = service.create(workspace, user, null).vm();

    clock.advance(Duration.ofMinutes(1));
    var ready = service.ready(vm.id());
    assertThat(ready.status()).isEqualTo(VmStatus.READY);
    assertThat(ready.lastSeenAt()).isEqualTo(clock.instant());

    var restarting = service.restart(vm.id());
    assertThat(restarting.status()).isEqualTo(VmStatus.RESTARTING);
    assertThat(restarting.lastSeenAt()).isEqualTo(ready.lastSeenAt());

    clock.advance(Duration.ofMinutes(1));
    var beat = service.heartbeat(vm.id());
    assertThat(beat.status()).isEqualTo(VmStatus.RESTARTING);
    assertThat(beat.lastSeenAt()).isEqualTo(clock.instant());
    assertThat(published).last().isEqualTo(new VmEvent.Changed(beat));
  }

  @Test
  void deletesTheServerThenTheVm() {
    var vm = service.create(workspace, user, null).vm();

    service.delete(vm.id());

    assertThat(hetzner.deleted).containsExactly(vm.serverId());
    assertThat(published).last().isEqualTo(new VmEvent.Deleted(vm.id()));
    assertThatThrownBy(() -> service.get(vm.id())).isInstanceOf(VmNotFoundException.class);
  }

  @Test
  void keepsTheVmWhenItsServerCouldNotBeDeleted() {
    var vm = service.create(workspace, user, null).vm();
    hetzner.failure = new HetznerException("Hetzner answered 503 on DELETE /servers/1");

    assertThatThrownBy(() -> service.delete(vm.id())).isSameAs(hetzner.failure);
    assertThat(service.get(vm.id())).isEqualTo(vm);
  }

  @Test
  void answersNotFoundForEveryChangeToAnUnknownVm() {
    var id = UUID.randomUUID();
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.ready(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.restart(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.heartbeat(id)).isInstanceOf(VmNotFoundException.class);
    assertThat(hetzner.deleted).isEmpty();
    assertThat(published).isEmpty();
  }
}

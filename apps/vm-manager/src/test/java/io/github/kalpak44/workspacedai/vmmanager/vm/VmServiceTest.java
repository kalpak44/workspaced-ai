package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The service on its own: an in-memory store, a hand-moved clock, and the events it publishes. */
class VmServiceTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
  final List<Object> published = new ArrayList<>();
  final VmService service =
      new VmService(
          new InMemoryVmRepository(),
          new VmProperties(List.of("cx23", "cx33")),
          published::add,
          clock);

  @Test
  void createsWithTheDefaultTypeAndPublishesIt() {
    var created = service.create("  owner-1 ", null);

    assertThat(created.isNew()).isTrue();
    var vm = created.vm();
    assertThat(vm.ownerRef()).isEqualTo("owner-1");
    assertThat(vm.serverType()).isEqualTo("cx23");
    assertThat(vm.status()).isEqualTo(VmStatus.PROVISIONING);
    assertThat(vm.createdAt()).isEqualTo(clock.instant());
    assertThat(published).containsExactly(new VmEvent.Changed(vm));
  }

  @Test
  void givesAnOwnerOneVmAndPublishesNothingTheSecondTime() {
    var first = service.create("owner-1", "cx33").vm();
    published.clear();

    var again = service.create("owner-1", "cx23");

    assertThat(again.isNew()).isFalse();
    assertThat(again.vm()).isEqualTo(first);
    assertThat(published).isEmpty();
  }

  @Test
  void refusesATypeNotOffered() {
    assertThatThrownBy(() -> service.create("owner-1", "cx53"))
        .isInstanceOf(ServerTypeNotOfferedException.class)
        .hasMessage("server type cx53 is not offered; choose one of cx23, cx33");
    assertThat(service.list(Optional.empty())).isEmpty();
  }

  @Test
  void listsOldestFirstAndByOwner() {
    var a = service.create("a", null).vm();
    clock.advance(Duration.ofSeconds(1));
    var b = service.create("b", null).vm();

    assertThat(service.list(Optional.empty())).containsExactly(a, b);
    assertThat(service.list(Optional.of("b"))).containsExactly(b);
  }

  @Test
  void followsReadyRestartAndHeartbeatOnTheClock() {
    var vm = service.create("owner-1", null).vm();

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
  void deletesAndPublishesTheDeletion() {
    var vm = service.create("owner-1", null).vm();

    service.delete(vm.id());

    assertThat(published).last().isEqualTo(new VmEvent.Deleted(vm.id()));
    assertThatThrownBy(() -> service.get(vm.id())).isInstanceOf(VmNotFoundException.class);
  }

  @Test
  void answersNotFoundForEveryChangeToAnUnknownVm() {
    var id = UUID.randomUUID();
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.ready(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.restart(id)).isInstanceOf(VmNotFoundException.class);
    assertThatThrownBy(() -> service.heartbeat(id)).isInstanceOf(VmNotFoundException.class);
    assertThat(published).isEmpty();
  }
}

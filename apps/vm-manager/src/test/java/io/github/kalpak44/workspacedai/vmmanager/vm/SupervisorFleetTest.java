package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupervisorFleetTest {
  @Test
  void reportsUnknownFailedRecoveredAndStalledVmsButExcludesStoppedOnes() {
    var clock = new MutableClock(Instant.parse("2026-11-28T00:00:00Z"));
    var repo = new InMemoryVmRepository();
    var service = new VmService(repo, null, null, null, null, null, null, null, null, null, clock);
    var properties =
        new SupervisorProperties(
            "new", "https://example.com", "a".repeat(64), "b".repeat(64), false, Set.of());
    var fleet = new SupervisorFleet(properties, service, clock);
    var id = UUID.randomUUID();
    repo.save(
        new Vm(
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "cx23",
            1,
            VmStatus.READY,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assertThat(fleet.metrics()).contains("workspaced_supervisor_behind{vm_id=\"" + id + "\"} 1");
    fleet.report(
        id,
        new SupervisorReport("old", "new", "failed", clock.instant(), "release checksum mismatch"));
    assertThat(fleet.metrics()).contains("workspaced_supervisor_failed{vm_id=\"" + id + "\"} 1");
    fleet.report(id, new SupervisorReport("new", "new", "current", clock.instant(), null));
    assertThat(fleet.metrics()).contains("workspaced_supervisor_behind{vm_id=\"" + id + "\"} 0");
    repo.update(id, vm -> vm.withStatus(VmStatus.RESTARTING));
    clock.advance(Duration.ofMinutes(16));
    fleet.alert();
    clock.advance(Duration.ofMinutes(16));
    fleet.alert();
    assertThat(fleet.metrics())
        .contains("workspaced_supervisor_restart_stalled{vm_id=\"" + id + "\"} 1");
    repo.update(id, vm -> vm.withStatus(VmStatus.STOPPED));
    assertThat(fleet.metrics()).doesNotContain(id.toString());
  }
}

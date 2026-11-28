package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Central fleet logs and periodic alerts; retained locally on each VM as journal.json too. */
@Component
public class SupervisorFleet {
  private static final Logger log = LoggerFactory.getLogger(SupervisorFleet.class);
  private final ConcurrentHashMap<UUID, SupervisorReport> reports = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Instant> restarting = new ConcurrentHashMap<>();
  private final SupervisorProperties properties;
  private final VmService vms;
  private final Clock clock;
  private final Instant started;

  public SupervisorFleet(SupervisorProperties properties, VmService vms, Clock clock) {
    this.properties = properties;
    this.vms = vms;
    this.clock = clock;
    this.started = clock.instant();
  }

  public void report(UUID id, SupervisorReport report) {
    if (report == null || !report.safe()) return;
    reports.put(id, report);
    log.info(
        "supervisor_status vmId={} installed={} desired={} state={} updateTime={} failure={}",
        id,
        report.installed(),
        report.desired(),
        report.state(),
        report.time(),
        report.failure());
    if ("failed".equals(report.state()) || "rolled_back".equals(report.state())) {
      log.warn(
          "supervisor_update_failed vmId={} installed={} desired={} reason={}",
          id,
          report.installed(),
          report.desired(),
          report.failure());
    }
  }

  public String metrics() {
    var output =
        new StringBuilder(
            "# TYPE workspaced_supervisor_fleet_up gauge\nworkspaced_supervisor_fleet_up 1\n");
    var now = clock.instant();
    for (var vm : vms.list(Optional.empty())) {
      if (vm.status() == VmStatus.STOPPED) continue;
      var report = reports.get(vm.id());
      var desired = properties.forVm(vm.id());
      boolean behind =
          desired != null && (report == null || !desired.version().equals(report.installed()));
      boolean failed =
          report != null
              && ("failed".equals(report.state()) || "rolled_back".equals(report.state()));
      var since = restarting.get(vm.id());
      boolean stalled =
          vm.status() == VmStatus.RESTARTING
              && since != null
              && Duration.between(since, now).toMinutes() >= 15;
      appendMetric(output, "behind", vm.id(), behind);
      appendMetric(output, "failed", vm.id(), failed);
      appendMetric(output, "restart_stalled", vm.id(), stalled);
    }
    return output.toString();
  }

  private static void appendMetric(StringBuilder output, String name, UUID id, boolean value) {
    output
        .append("workspaced_supervisor_")
        .append(name)
        .append("{vm_id=\"")
        .append(id)
        .append("\"} ")
        .append(value ? 1 : 0)
        .append('\n');
  }

  @Scheduled(fixedDelay = 60000)
  public void alert() {
    var now = clock.instant();
    // Allow inventory and the first heartbeat to recover after control-plane restart.
    if (Duration.between(started, now).toMinutes() < 15) return;
    var live = new java.util.HashSet<UUID>();
    for (var vm : vms.list(Optional.empty())) {
      live.add(vm.id());
      if (vm.status() == VmStatus.STOPPED) {
        restarting.remove(vm.id());
        continue;
      }
      var desired = properties.forVm(vm.id());
      var report = reports.get(vm.id());
      if (desired != null && (report == null || !desired.version().equals(report.installed()))) {
        log.warn(
            "supervisor_version_behind vmId={} installed={} desired={}",
            vm.id(),
            report == null ? "unknown" : report.installed(),
            desired.version());
      }
      if (vm.status() == VmStatus.RESTARTING) {
        var since = restarting.computeIfAbsent(vm.id(), id -> now);
        if (Duration.between(since, now).toMinutes() >= 15)
          log.warn("supervisor_restart_stalled vmId={}", vm.id());
      } else {
        restarting.remove(vm.id());
      }
    }
    reports.keySet().retainAll(live);
    restarting.keySet().retainAll(live);
  }
}

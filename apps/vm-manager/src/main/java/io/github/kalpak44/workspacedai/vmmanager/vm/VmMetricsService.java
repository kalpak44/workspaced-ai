package io.github.kalpak44.workspacedai.vmmanager.vm;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.ServerType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * A VM's metrics: CPU, disk and network from Hetzner, memory from the VM's own heartbeat. The
 * memory reading lives in memory only, because the next heartbeat replaces it within 30 seconds.
 */
@Service
public class VmMetricsService {

  /** Our series name for each of Hetzner's, in the order they are answered. */
  public static final Map<String, String> SERIES = series();

  // Two missed heartbeats; an older reading would show a VM that stopped reporting as healthy.
  static final Duration MEMORY_FRESH_FOR = Duration.ofSeconds(75);

  private final VmService vms;
  private final VmOptionService options;
  private final HetznerClient hetzner;
  private final Clock clock;
  private final Map<UUID, VmMemory> memory = new ConcurrentHashMap<>();
  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

  public VmMetricsService(
      VmService vms, VmOptionService options, HetznerClient hetzner, Clock clock) {
    this.vms = vms;
    this.options = options;
    this.hetzner = hetzner;
    this.clock = clock;
  }

  private static Map<String, String> series() {
    var series = new LinkedHashMap<String, String>();
    series.put("cpu", "cpu");
    series.put("disk.0.bandwidth.read", "diskReadBytes");
    series.put("disk.0.bandwidth.write", "diskWriteBytes");
    series.put("disk.0.iops.read", "diskReadIops");
    series.put("disk.0.iops.write", "diskWriteIops");
    series.put("network.0.bandwidth.in", "networkInBytes");
    series.put("network.0.bandwidth.out", "networkOutBytes");
    series.put("network.0.pps.in", "networkInPackets");
    series.put("network.0.pps.out", "networkOutPackets");
    return Collections.unmodifiableMap(series);
  }

  /** Keeps the VM's latest memory reading, replacing the one before it. */
  public void report(UUID vmId, long totalBytes, long availableBytes) {
    memory.put(vmId, new VmMemory(totalBytes, availableBytes, clock.instant()));
  }

  /**
   * CPU is a percentage of all the VM's cores, where Hetzner counts 100 per core.
   *
   * @throws VmNotFoundException when there is no such VM
   */
  public VmMetrics metrics(UUID vmId, MetricsRange range) {
    var vm = vms.get(vmId);
    var type = serverType(vm.serverType());
    var now = clock.instant();
    var key = new Key(vmId, range);
    var cached = cache.get(key);
    if (cached == null || !now.isBefore(cached.takenAt().plus(range.cacheFor()))) {
      var raw = hetzner.metrics(vm.serverId(), now.minus(range.span()), now, range.step());
      cached =
          new Cached(
              raw.timestamps(),
              translate(raw.series(), raw.timestamps().size(), type.cores()),
              now);
      cache.put(key, cached);
    }
    var reading = memory.get(vmId);
    var fresh = reading != null && now.isBefore(reading.reportedAt().plus(MEMORY_FRESH_FOR));
    return new VmMetrics(
        type.cores(),
        type.memoryGb(),
        cached.timestamps(),
        cached.series(),
        fresh ? reading : null);
  }

  @EventListener
  void forget(VmEvent.Deleted deleted) {
    memory.remove(deleted.id());
    cache.keySet().removeIf(key -> key.vmId().equals(deleted.id()));
  }

  // A type no longer offered is still the size of the VMs created as it, so Hetzner is asked.
  private Size serverType(String name) {
    return options.list().stream()
        .filter(option -> option.serverType().equals(name))
        .findFirst()
        .map(option -> new Size(option.cores(), option.memoryGb()))
        .or(
            () ->
                hetzner.serverTypes().stream()
                    .filter(type -> type.name().equals(name))
                    .findFirst()
                    .map(VmMetricsService::size))
        .orElseThrow(() -> new IllegalStateException("Hetzner has no server type " + name));
  }

  private static Size size(ServerType type) {
    return new Size(type.cores(), type.memoryGb());
  }

  // A series Hetzner left out is answered as all gaps, so every chart has the same timestamps.
  private static Map<String, List<Double>> translate(
      Map<String, List<Double>> raw, int samples, int cores) {
    var translated = new LinkedHashMap<String, List<Double>>();
    SERIES.forEach(
        (hetznerName, name) -> {
          var values = raw.get(hetznerName);
          var out = new ArrayList<Double>(samples);
          for (var i = 0; i < samples; i++) {
            var value = values == null || i >= values.size() ? null : values.get(i);
            out.add(value != null && name.equals("cpu") ? Double.valueOf(value / cores) : value);
          }
          translated.put(name, Collections.unmodifiableList(out));
        });
    return Collections.unmodifiableMap(translated);
  }

  private record Key(UUID vmId, MetricsRange range) {}

  private record Cached(
      List<Instant> timestamps, Map<String, List<Double>> series, Instant takenAt) {}

  private record Size(int cores, double memoryGb) {}
}

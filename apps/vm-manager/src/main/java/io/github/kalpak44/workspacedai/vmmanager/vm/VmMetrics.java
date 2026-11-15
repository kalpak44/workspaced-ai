package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One range of a VM's metrics: Hetzner's series, and the memory reading its heartbeat carries,
 * since Hetzner has none.
 *
 * @param series keyed as {@link VmMetricsService#SERIES} names them, one value per timestamp
 * @param memory null when the VM has not reported recently
 */
public record VmMetrics(
    int cores,
    double memoryGb,
    List<Instant> timestamps,
    Map<String, List<Double>> series,
    VmMemory memory) {}

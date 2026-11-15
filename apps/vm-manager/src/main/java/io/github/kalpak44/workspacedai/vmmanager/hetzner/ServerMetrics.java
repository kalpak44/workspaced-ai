package io.github.kalpak44.workspacedai.vmmanager.hetzner;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A server's metrics over one period as Hetzner reports them, every series aligned to the same
 * timestamps.
 *
 * @param timestamps oldest first
 * @param series by Hetzner's name, e.g. {@code disk.0.iops.read}, one value per timestamp; null
 *     where Hetzner had no sample
 */
public record ServerMetrics(List<Instant> timestamps, Map<String, List<Double>> series) {}

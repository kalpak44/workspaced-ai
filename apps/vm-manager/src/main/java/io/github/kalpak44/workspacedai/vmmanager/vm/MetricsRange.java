package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How far back a VM's metrics reach and how finely they are sampled. Every step keeps the samples
 * under Hetzner's cap of 500 per answer.
 *
 * @param cacheFor how long one answer from Hetzner is reused, since every viewer of a chart would
 *     otherwise spend the project's hourly request limit
 */
public enum MetricsRange {
  LIVE("live", Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(30)),
  HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofMinutes(1)),
  DAY("24h", Duration.ofHours(24), Duration.ofMinutes(15), Duration.ofMinutes(5)),
  WEEK("7d", Duration.ofDays(7), Duration.ofHours(2), Duration.ofMinutes(15)),
  MONTH("30d", Duration.ofDays(30), Duration.ofHours(6), Duration.ofMinutes(30));

  private final String id;
  private final Duration span;
  private final Duration step;
  private final Duration cacheFor;

  MetricsRange(String id, Duration span, Duration step, Duration cacheFor) {
    this.id = id;
    this.span = span;
    this.step = step;
    this.cacheFor = cacheFor;
  }

  /**
   * @throws MetricsRangeNotOfferedException when no range has that id
   */
  public static MetricsRange of(String id) {
    return Arrays.stream(values())
        .filter(range -> range.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new MetricsRangeNotOfferedException(id, ids()));
  }

  static String ids() {
    return Arrays.stream(values()).map(MetricsRange::id).collect(Collectors.joining(", "));
  }

  public String id() {
    return id;
  }

  public Duration span() {
    return span;
  }

  public Duration step() {
    return step;
  }

  public Duration cacheFor() {
    return cacheFor;
  }
}

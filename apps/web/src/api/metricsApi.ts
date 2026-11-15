export type MetricsRange = "live" | "1h" | "24h" | "7d" | "30d";
export type MetricUnit = "percent" | "bytes" | "iops" | "pps";
export type MetricId = "cpu" | "memory" | "disk-throughput" | "disk-iops" | "network-traffic" | "network-pps";

export interface MetricSeries {
  readonly key: string;
  readonly label: string;
  /** One value per timestamp, in the metric's unit (bytes are per second). */
  readonly values: readonly number[];
}

export interface Metric {
  readonly id: MetricId;
  readonly title: string;
  readonly unit: MetricUnit;
  readonly series: readonly MetricSeries[];
}

/** One range of a workspace VM's metrics, every series sampled at the same timestamps. */
export interface Metrics {
  readonly cores: number;
  readonly memoryGb: number;
  /** Epoch milliseconds, oldest first. */
  readonly timestamps: readonly number[];
  readonly metrics: readonly Metric[];
}

/** How far back each range reaches and how often it samples, in seconds. */
export const RANGES: Record<MetricsRange, { readonly span: number; readonly step: number }> = {
  live: { span: 5 * 60, step: 5 },
  "1h": { span: 3600, step: 60 },
  "24h": { span: 24 * 3600, step: 15 * 60 },
  "7d": { span: 7 * 24 * 3600, step: 2 * 3600 },
  "30d": { span: 30 * 24 * 3600, step: 6 * 3600 },
};

// The API has no metrics route yet; this generator lets the page be built first. Values are
// derived from the workspace, series and time bucket, so a refresh moves forward instead of
// redrawing a different history. Swap it for a send() once the VM manager reports real ones.
function noise(seed: string, bucket: number): number {
  let hash = 2166136261;
  for (const char of `${seed}:${bucket}`) {
    hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  }
  return (hash >>> 0) / 4294967295;
}

/** A quiet baseline with occasional bursts, the shape an idle agent VM actually has. */
function bursty(seed: string, buckets: readonly number[], base: number, peak: number, chance: number): number[] {
  return buckets.map((bucket) => {
    const burst = noise(`${seed}:b`, bucket) < chance ? noise(`${seed}:p`, bucket) * peak : 0;
    return Math.max(0, base * (0.6 + noise(seed, bucket) * 0.8) + burst);
  });
}

export async function fetchMetrics(workspaceId: string, range: MetricsRange, now = Date.now()): Promise<Metrics> {
  const { span, step } = RANGES[range];
  const last = Math.floor(now / 1000 / step);
  const buckets = Array.from({ length: Math.round(span / step) }, (_, i) => last - Math.round(span / step) + 1 + i);
  const seed = (name: string) => `${workspaceId}:${name}`;
  const busy = range === "live" || range === "1h" ? 0.12 : 0.3;
  const memory = bursty(seed("mem"), buckets, 38, 22, busy).map((value, i) => Math.min(96, value * 0.4 + 30 + (i % 12)));
  return {
    cores: 2,
    memoryGb: 4,
    timestamps: buckets.map((bucket) => bucket * step * 1000),
    metrics: [
      { id: "cpu", title: "CPU", unit: "percent", series: [{ key: "cpu", label: "CPU", values: bursty(seed("cpu"), buckets, 2, 9, busy).map((v) => Math.min(100, v)) }] },
      { id: "memory", title: "Memory", unit: "percent", series: [{ key: "used", label: "Used", values: memory }] },
      {
        id: "disk-throughput", title: "Disk throughput", unit: "bytes", series: [
          { key: "read", label: "Read", values: bursty(seed("dr"), buckets, 500, 9_000, busy) },
          { key: "write", label: "Write", values: bursty(seed("dw"), buckets, 3_000, 55_000, busy * 1.6) },
        ],
      },
      {
        id: "disk-iops", title: "Disk IOPS", unit: "iops", series: [
          { key: "read", label: "Read", values: bursty(seed("ir"), buckets, 0.1, 2.5, busy) },
          { key: "write", label: "Write", values: bursty(seed("iw"), buckets, 0.4, 5.5, busy * 1.6) },
        ],
      },
      {
        id: "network-traffic", title: "Network traffic", unit: "bytes", series: [
          { key: "in", label: "In", values: bursty(seed("ni"), buckets, 300, 2_500, busy) },
          { key: "out", label: "Out", values: bursty(seed("no"), buckets, 200, 36_000, busy * 0.5) },
        ],
      },
      {
        id: "network-pps", title: "Network PPS", unit: "pps", series: [
          { key: "in", label: "In", values: bursty(seed("pi"), buckets, 0.6, 12, busy) },
          { key: "out", label: "Out", values: bursty(seed("po"), buckets, 0.5, 13, busy) },
        ],
      },
    ],
  };
}

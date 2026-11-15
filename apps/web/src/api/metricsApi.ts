import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

export type MetricsRange = "live" | "1h" | "24h" | "7d" | "30d";
export type MetricUnit = "percent" | "bytes" | "iops" | "pps";
export type MetricId = "cpu" | "disk-throughput" | "disk-iops" | "network-traffic" | "network-pps";

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

/** The VM's memory as its last heartbeat reported it; there is no history of it. */
export interface Memory {
  readonly totalBytes: number;
  readonly availableBytes: number;
}

/** One range of a workspace VM's metrics, every series sampled at the same timestamps. */
export interface Metrics {
  readonly cores: number;
  readonly memoryGb: number;
  /** Epoch milliseconds, oldest first. */
  readonly timestamps: readonly number[];
  readonly metrics: readonly Metric[];
  /** Null while the VM has not reported recently. */
  readonly memory: Memory | null;
}

export interface MetricsResponse {
  readonly cores: number;
  readonly memoryGb: number;
  readonly timestamps: readonly string[];
  readonly series: Readonly<Record<string, readonly (number | null)[]>>;
  readonly memory: (Memory & { readonly reportedAt: string }) | null;
}

// Each chart's series, as [key, the API's series name, label].
const CHARTS: ReadonlyArray<{ id: MetricId; title: string; unit: MetricUnit; series: ReadonlyArray<readonly [string, string, string]> }> = [
  { id: "cpu", title: "CPU", unit: "percent", series: [["cpu", "cpu", "CPU"]] },
  { id: "disk-throughput", title: "Disk throughput", unit: "bytes", series: [["read", "diskReadBytes", "Read"], ["write", "diskWriteBytes", "Write"]] },
  { id: "disk-iops", title: "Disk IOPS", unit: "iops", series: [["read", "diskReadIops", "Read"], ["write", "diskWriteIops", "Write"]] },
  { id: "network-traffic", title: "Network traffic", unit: "bytes", series: [["in", "networkInBytes", "In"], ["out", "networkOutBytes", "Out"]] },
  { id: "network-pps", title: "Network PPS", unit: "pps", series: [["in", "networkInPackets", "In"], ["out", "networkOutPackets", "Out"]] },
];

/**
 * Charts draw a number at every timestamp. The newest sample is often one Hetzner has not filled
 * yet, so a trailing sample empty in every series is dropped; any other gap holds the value
 * before it, so a missed sample reads as flat rather than as a drop to zero.
 */
export function toMetrics(response: MetricsResponse): Metrics {
  const names = CHARTS.flatMap((chart) => chart.series.map(([, name]) => name));
  let count = response.timestamps.length;
  while (count > 0 && names.every((name) => response.series[name]?.[count - 1] == null)) count--;
  const values = (name: string) => {
    let last = 0;
    return Array.from({ length: count }, (_, i) => (last = response.series[name]?.[i] ?? last));
  };
  return {
    cores: response.cores,
    memoryGb: response.memoryGb,
    timestamps: response.timestamps.slice(0, count).map((at) => Date.parse(at)),
    metrics: CHARTS.map(({ id, title, unit, series }) => ({
      id, title, unit, series: series.map(([key, name, label]) => ({ key, label, values: values(name) })),
    })),
    memory: response.memory && { totalBytes: response.memory.totalBytes, availableBytes: response.memory.availableBytes },
  };
}

export async function fetchMetrics(workspaceId: string, range: MetricsRange): Promise<Metrics> {
  const response = await send(`${workspaceUrl(workspaceId)}/metrics?range=${range}`);
  return toMetrics((await response.json()) as MetricsResponse);
}

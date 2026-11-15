import { Activity, Cpu, HardDrive, MemoryStick, Network } from "lucide-react";
import { useState } from "react";
import { type Metric, type MetricId, type MetricsRange, useMetrics } from "../../hooks/useMetrics";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Select } from "../atoms";
import { EmptyState } from "../molecules";
import { formatMetric, MetricChart } from "../organisms";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

const RANGE_OPTIONS: ReadonlyArray<readonly [MetricsRange, string]> = [
  ["live", "Live"], ["1h", "Last hour"], ["24h", "Last 24 hours"], ["7d", "Last 7 days"], ["30d", "Last 30 days"],
];

const CLOCK = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const MINUTES = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });
const DAY = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" });

const TIME_FORMAT: Record<MetricsRange, (at: number) => string> = {
  live: (at) => CLOCK.format(at),
  "1h": (at) => MINUTES.format(at),
  "24h": (at) => MINUTES.format(at),
  "7d": (at) => DAY.format(at),
  "30d": (at) => DAY.format(at),
};

/** The latest sample of a metric, summed across its series, as a tile reads it. */
function latest(metrics: readonly Metric[], id: MetricId) {
  const metric = metrics.find((m) => m.id === id);
  if (!metric) return null;
  const parts = metric.series.map((s) => `${s.label} ${formatMetric(s.values.at(-1) ?? 0, metric.unit)}`);
  const total = metric.series.reduce((sum, s) => sum + (s.values.at(-1) ?? 0), 0);
  return { value: formatMetric(total, metric.unit), detail: metric.series.length > 1 ? parts.join(" · ") : null };
}

interface Props {
  readonly workspace: Workspace;
  readonly deleting: boolean;
}

/** How hard a workspace's VM is working: CPU, memory, disk and network over a chosen range. */
export function MonitoringPage({ workspace, deleting }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const [range, setRange] = useState<MetricsRange>("live");
  const { metrics, status } = useMetrics(ready ? workspace.id : null, range);
  const format = TIME_FORMAT[range];

  const tiles = metrics && [
    { label: "CPU", icon: Cpu, ...latest(metrics.metrics, "cpu"), detail: `${metrics.cores} vCPU` },
    { label: "Memory", icon: MemoryStick, ...latest(metrics.metrics, "memory"), detail: `of ${metrics.memoryGb} GB` },
    { label: "Disk", icon: HardDrive, ...latest(metrics.metrics, "disk-throughput") },
    { label: "Network", icon: Network, ...latest(metrics.metrics, "network-traffic") },
  ];

  return (
    <>
      {/* The tab above already says Monitoring; the title stays for readers and the outline. */}
      <div className="mx-auto flex w-full max-w-5xl items-center justify-between gap-3 px-4 pt-6 sm:px-6">
        <h1 className="sr-only">Monitoring</h1>
        <p className="min-w-0 text-sm text-zinc-500 dark:text-zinc-400">How hard this workspace’s machine is working.</p>
        {ready && (
          <div className="flex shrink-0 items-center gap-3">
            {range === "live" && (
              <span className="flex items-center gap-1.5 text-xs text-zinc-500 max-sm:sr-only dark:text-zinc-400">
                <span aria-hidden className="size-2 animate-pulse rounded-full bg-emerald-500" />Updating live
              </span>
            )}
            <Select aria-label="Time range" value={range} onChange={(event) => setRange(event.target.value as MetricsRange)}>
              {RANGE_OPTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </Select>
          </div>
        )}
      </div>
      {!ready ? <WorkspaceStatusView workspace={workspace} deleting={deleting} />
        : status === "failed" ? <EmptyState title="Metrics are unavailable" hint="The workspace's metrics could not be loaded. Please try again later." />
        : !metrics ? <EmptyState busy title="Loading metrics…" hint="Fetching the workspace's metrics" />
        : (
          <div className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-4 pt-4 pb-10 sm:px-6">
            <ul aria-label="Now" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              {tiles?.map(({ label, icon: Icon, value, detail }) => (
                <li key={label} className="card flex min-w-0 flex-col gap-1 p-4">
                  <span className="flex items-center gap-2 text-xs font-medium text-zinc-500 dark:text-zinc-400">
                    <span className="flex size-6 items-center justify-center rounded-md bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"><Icon aria-hidden className="size-3.5" /></span>{label}
                  </span>
                  <span className="mt-1 truncate text-xl font-semibold tracking-tight tabular-nums sm:text-2xl">{value}</span>
                  {detail && <span className="truncate text-xs text-zinc-500 dark:text-zinc-400">{detail}</span>}
                </li>
              ))}
            </ul>
            <div className="grid gap-4 xl:grid-cols-2">
              {metrics.metrics.map((metric) => (
                <MetricChart key={metric.id} metric={metric} timestamps={metrics.timestamps} formatTime={format}
                  note={metric.id === "cpu" ? `100% = all ${metrics.cores} vCPU` : metric.id === "memory" ? `100% = ${metrics.memoryGb} GB` : undefined} />
              ))}
            </div>
            <p className="flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
              <Activity aria-hidden className="size-3.5" />
              {range === "live" ? "Sampled every 5 seconds." : `Showing ${RANGE_OPTIONS.find(([value]) => value === range)?.[1].toLowerCase()}.`} Times are in your time zone.
            </p>
          </div>
        )}
    </>
  );
}

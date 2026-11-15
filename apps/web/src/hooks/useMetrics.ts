import { useEffect, useState } from "react";
import { fetchMetrics, type Metrics, type MetricsRange } from "../api/metricsApi";

export type { Memory, Metric, MetricId, Metrics, MetricSeries, MetricsRange, MetricUnit } from "../api/metricsApi";

/** How often Live asks again; the server reuses Hetzner's answer for as long, so asking sooner adds nothing. */
export const LIVE_REFRESH_MS = 30_000;

/** One range of a workspace's metrics; Live refreshes itself, the fixed ranges load once. */
export function useMetrics(workspaceId: string | null, range: MetricsRange) {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");

  useEffect(() => {
    if (workspaceId === null) return;
    let active = true;
    const load = () => fetchMetrics(workspaceId, range).then(
      (result) => {
        if (active) {
          setMetrics(result);
          setStatus("ready");
        }
      },
      () => active && setStatus("failed"),
    );
    setStatus("loading");
    void load();
    const timer = range === "live" ? setInterval(() => void load(), LIVE_REFRESH_MS) : undefined;
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [workspaceId, range]);

  return { metrics, status };
}

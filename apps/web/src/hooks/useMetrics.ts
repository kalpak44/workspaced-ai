import { useEffect, useState } from "react";
import { fetchMetrics, type Metrics, type MetricsRange } from "../api/metricsApi";

export { RANGES } from "../api/metricsApi";
export type { Metric, MetricId, Metrics, MetricSeries, MetricsRange, MetricUnit } from "../api/metricsApi";

/** How often Live asks again; one sample step, so each refresh adds one point. */
export const LIVE_REFRESH_MS = 5_000;

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

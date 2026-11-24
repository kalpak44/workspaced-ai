import { useEffect, useState } from "react";
import { type Funnel, getFunnel } from "../api/funnelApi";

export type { Funnel, FunnelCounts, FunnelSource, FunnelTourStep } from "../api/funnelApi";

const DAY_MS = 86_400_000;
const day = (date: Date) => date.toISOString().slice(0, 10);

/** The admin's sign-up funnel for the last `days` days, today included. */
export function useFunnel(days: number) {
  const [funnel, setFunnel] = useState<Funnel | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    const to = new Date();
    setFunnel(null);
    setError(null);
    getFunnel(day(new Date(to.getTime() - (days - 1) * DAY_MS)), day(to))
      .then((value) => { if (current) setFunnel(value); })
      .catch(() => { if (current) setError("Could not load the funnel."); });
    return () => { current = false; };
  }, [days, attempt]);

  return { funnel, error, retry: () => setAttempt((n) => n + 1) };
}

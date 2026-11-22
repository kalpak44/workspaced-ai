import { useAiConnectors } from "./useAiConnectors";
import { useConnectorMetrics } from "./useConnectorMetrics";

/** Remaining OpenRouter credit on the account's one lifetime-capped trial key, never a token estimate. */
export function useTrialConnectorBudget(id: string) {
  const usage = useConnectorMetrics(id, 30_000);
  const reported = usage.metrics?.limitRemaining
    ?? (usage.metrics?.usage == null ? null : 1 - usage.metrics.usage);
  const remaining = reported !== null && Number.isFinite(reported) ? Math.max(0, Math.min(1, reported)) : null;
  return { remaining, loading: usage.loading, error: !!usage.error };
}

export function useTrialBudget() {
  const connectors = useAiConnectors();
  const trial = connectors.connectors.find(connector => connector.trial);
  const budget = useTrialConnectorBudget(trial?.id ?? "");
  return { ...budget, loading: connectors.loading || budget.loading, error: !!connectors.error || budget.error };
}

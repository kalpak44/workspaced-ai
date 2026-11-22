import { useTrialConnectorBudget } from "../../hooks/useTrialBudget";
import { TrialBudgetProgress } from "../molecules/TrialBudgetProgress";

/** The default connector shows only its remaining budget, never personal-key controls. */
export function TrialConnectorBudget({ id }: { readonly id: string }) {
  const budget = useTrialConnectorBudget(id);
  return <TrialBudgetProgress remaining={budget.remaining} loading={budget.loading} stale={budget.error} />;
}

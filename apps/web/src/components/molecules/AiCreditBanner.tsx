import { TriangleAlert } from "lucide-react";
import type { WorkspaceAiCredits } from "../../hooks/useWorkspaceAiCredits";
import { routePath } from "../../lib/routes";

/** Shared credit warnings near the composer, including trial warnings below $0.20. */
export function AiCreditBanner({ status, owner, onRefresh }: {
  readonly status: WorkspaceAiCredits | null;
  readonly owner: boolean;
  readonly onRefresh: () => void;
}) {
  if (!status || (!status.low && !status.exhausted)) return null;
  const remaining = status.remainingUsd === null ? "" : new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(status.remainingUsd);
  return <div role={status.exhausted || status.trial ? "alert" : "status"} aria-label="AI credits" className={`mb-3 flex items-start gap-2.5 rounded-xl border px-3 py-2.5 text-xs leading-5 sm:text-sm ${status.exhausted ? "border-rose-200 bg-rose-50/70 text-rose-900 dark:border-rose-950 dark:bg-rose-950/30 dark:text-rose-200" : "border-amber-200 bg-amber-50/70 text-amber-900 dark:border-amber-950 dark:bg-amber-950/30 dark:text-amber-200"}`}>
    <TriangleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
    <div className="min-w-0 flex-1">
      <p><span className="font-medium">{status.exhausted ? "AI credits have run out." : `${status.trial ? "Trial AI" : "AI"} credits are running low · ${remaining} left.`}</span>{" "}
        {status.trial ? owner ? "Add your own funded AI connector to keep working." : "Ask the workspace owner to add a funded AI connector." : owner ? status.exhausted ? "Top up or select a funded connector to send messages." : "Top up your AI connector to keep working." : "Ask the workspace owner to top up."}</p>
      <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 font-medium">
        {owner && !status.trial && <a href="https://openrouter.ai/settings/credits" target="_blank" rel="noreferrer" className="underline underline-offset-2">Top up AI credits</a>}
        {owner && <a href={routePath({ name: "general-settings", section: "ai-connectors" })} className="underline underline-offset-2">AI connector settings</a>}
        <button type="button" onClick={onRefresh} className="underline underline-offset-2">Check again</button>
      </div>
    </div>
  </div>;
}

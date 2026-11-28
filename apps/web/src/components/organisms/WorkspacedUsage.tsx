import { RefreshCw } from "lucide-react";
import { useWorkspacedUsage } from "../../hooks/useWorkspacedUsage";
import { Button } from "../atoms";

const number = new Intl.NumberFormat("en-US");
/** Workspaced's measured steps are separate from provider-wide balances and quotas. */
export function WorkspacedUsage({ connectorId }: { readonly connectorId: string }) {
  const { rows, error, busy, refresh } = useWorkspacedUsage(connectorId);
  return <section aria-label="Workspaced token usage" className="card min-w-0 space-y-4 p-4 sm:p-5">
    <header className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-sm font-medium">Workspaced token usage</h3><Button label="Refresh token usage" icon={RefreshCw} variant="secondary" busy={busy} onClick={refresh} /></header>
    <p className="text-xs leading-5 text-zinc-500">Recorded completed model steps in Workspaced, grouped by workspace and model. Collection can take about 30 seconds. These are measured tokens, not a provider balance or remaining ChatGPT allowance.</p>
    {error && <p role="alert" className="text-sm text-rose-600">Could not load token usage. Refresh to try again.{rows && " Previously loaded figures are shown."}</p>}
    {!rows && !error && <p role="status" className="text-sm text-zinc-500">Loading token usage…</p>}
    {rows?.length === 0 && <p className="text-sm text-zinc-500">No attributed completed steps recorded yet.</p>}
    {rows && rows.length > 0 && <ul className="space-y-3">{rows.map(row => <li key={`${row.workspaceId}/${row.provider}/${row.model}`} className="min-w-0 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
      <h4 className="break-words text-sm font-medium">{row.workspaceName}</h4><p className="mt-1 break-all text-xs text-zinc-500">{row.provider} · {row.model}</p>
      <dl className="mt-3 grid grid-cols-2 gap-3 text-xs sm:grid-cols-3">{[
        ["Completed steps", row.steps], ["Input tokens", row.input], ["Output tokens", row.output],
        ["Reasoning tokens", row.reasoning], ["Cache read tokens", row.cacheRead], ["Cache write tokens", row.cacheWrite],
      ].map(([label, value]) => <div key={label}><dt className="text-zinc-500">{label}</dt><dd className="mt-1 break-all font-medium tabular-nums">{number.format(value as number)}</dd></div>)}</dl>
    </li>)}</ul>}
    <p className="text-xs leading-5 text-zinc-500">Only calls with a matching Workspaced dispatch and a completed token receipt are included. Historical calls, title generation, compaction, and untracked local or child-agent calls are excluded. The harness reports cache and reasoning separately from its input and output counts.</p>
  </section>;
}

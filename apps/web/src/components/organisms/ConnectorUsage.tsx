import { Activity, CalendarDays, CalendarRange, Clock, CreditCard, ExternalLink, RefreshCw } from "lucide-react";
import { useConnectorMetrics } from "../../hooks/useConnectorMetrics";
import { Button } from "../atoms";
import { EmptyState } from "../molecules";
import { MetricTile } from "../molecules/MetricTile";

const USD = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 4 });
const money = (value: number | null) => value === null ? "—" : USD.format(value);

/** OpenRouter reports aggregate spend, so these share Monitoring's tiles without inventing a timeline. */
export function ConnectorUsage({ id, name, trial = false }: { readonly id: string; readonly name: string; readonly trial?: boolean }) {
  const { metrics, updatedAt, loading, error, refresh } = useConnectorMetrics(id);
  const tiles = metrics && [
    { label: "Total spend", icon: CreditCard, value: metrics.usage, detail: "OpenRouter credits · USD" },
    { label: "Today", icon: Clock, value: metrics.usageDaily, detail: "Current UTC day · USD" },
    { label: "This week", icon: CalendarDays, value: metrics.usageWeekly, detail: "Current UTC week · USD" },
    { label: "This month", icon: CalendarRange, value: metrics.usageMonthly, detail: "Current UTC month · USD" },
  ];
  return <section aria-label={`Usage for ${name}`} className="flex min-w-0 flex-col gap-4">
    <header className="flex items-center justify-between gap-3">
      <div className="min-w-0"><h3 className="break-words text-sm font-medium">{name} · Usage and limits</h3><p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">Reported by OpenRouter · amounts in USD</p></div>
      <Button label="Refresh usage" icon={RefreshCw} variant="secondary" busy={loading} onClick={refresh} />
    </header>
    {!metrics && (error
      ? <div role="alert" className="card"><EmptyState title="Metrics are unavailable" hint={error} action={{ label: "Retry usage", icon: RefreshCw, onClick: refresh }} /></div>
      : <div className="card"><EmptyState busy title="Loading metrics…" hint="Fetching this key's usage from OpenRouter" /></div>)}
    {metrics && <>
      {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error} The figures below are from the last successful check.</p>}
      <ul aria-label="Key usage" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles?.map(tile => <MetricTile key={tile.label} label={tile.label} icon={tile.icon} value={money(tile.value)} detail={tile.value === null ? "Not reported yet" : tile.detail} />)}
      </ul>
      <div className="grid gap-4 xl:grid-cols-2">
        <section aria-label="Key limits" className="card flex min-w-0 flex-col gap-3 p-4 sm:p-5">
          <h4 className="text-sm font-medium">Key limits</h4>
          <dl className="space-y-3 text-sm">
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">Spending cap</dt><dd className="font-medium tabular-nums">{metrics.limit === null ? "No key cap reported" : money(metrics.limit)}</dd></div>
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">Remaining under key limit</dt><dd className="font-medium tabular-nums">{metrics.limitRemaining === null ? "Not reported" : money(metrics.limitRemaining)}</dd></div>
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">Limit reset</dt><dd>{metrics.limitReset ?? "No reset reported"}</dd></div>
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">Expiry</dt><dd>{metrics.expiresAt ? new Date(metrics.expiresAt).toLocaleString() : "No expiry reported"}</dd></div>
          </dl>
          <p className="mt-auto text-xs leading-5 text-zinc-500 dark:text-zinc-400">A key spending cap is separate from your account balance and other provider restrictions.</p>
        </section>
        <section aria-label="Provider usage" className="card flex min-w-0 flex-col gap-3 p-4 sm:p-5">
          <h4 className="text-sm font-medium">Provider usage</h4>
          <dl className="space-y-3 text-sm">
            <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">External provider-key spend (BYOK)</dt><dd className="font-medium tabular-nums">{money(metrics.byokUsage)}</dd></div>
            {metrics.includeByokInLimit !== null && <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">BYOK counts toward cap</dt><dd>{metrics.includeByokInLimit ? "Yes" : "No"}</dd></div>}
            {metrics.freeRequestsLimit !== null && <div className="flex flex-wrap justify-between gap-x-4 gap-y-1"><dt className="text-zinc-500 dark:text-zinc-400">Free-model requests today</dt><dd className="tabular-nums">{metrics.freeRequestsRemaining ?? "Unknown"} remaining of {metrics.freeRequestsLimit}</dd></div>}
          </dl>
          <p className="mt-auto text-xs leading-5 text-zinc-500 dark:text-zinc-400">Totals cover all use of this key, including other apps and workspaces.</p>
        </section>
      </div>
      <p className="flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400"><Activity aria-hidden className="size-3.5 shrink-0" />Usage periods follow UTC. {updatedAt && `Checked ${updatedAt.toLocaleTimeString()}.`} Refresh to check again.</p>
    </>}
    {trial ? <div className="card space-y-2 p-4 sm:p-5"><h4 className="text-sm font-medium">Your Trial connector</h4><p className="text-xs leading-5 text-zinc-500 dark:text-zinc-400">Provided by Workspaced with a small one-time budget. It is your default connector, cannot be deleted, and does not reset or refill. When it runs out, add your own AI connector to continue.</p></div> : <div className="card flex min-w-0 flex-col gap-3 p-4 sm:p-5">
      <h4 className="text-sm font-medium">Manage in OpenRouter</h4>
      <p className="text-xs leading-5 text-zinc-500 dark:text-zinc-400">Change spending limits and expiry in OpenRouter. Existing-key changes require an OpenRouter management key and cannot be made with this connector. Request rate limits are controlled by OpenRouter and the model provider.</p>
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm"><a href="https://openrouter.ai/settings/keys" target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 underline underline-offset-4">Manage limits in OpenRouter<ExternalLink aria-hidden className="size-3" /></a><a href="https://openrouter.ai/activity" target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 underline underline-offset-4">Detailed activity<ExternalLink aria-hidden className="size-3" /></a></div>
    </div>}
  </section>;
}

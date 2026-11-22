/** One shared budget bar for the demo banner and the protected default connector. */
export function TrialBudgetProgress({ remaining, loading = false, stale = false }: {
  readonly remaining: number | null;
  readonly loading?: boolean;
  readonly stale?: boolean;
}) {
  const percentage = remaining === null ? null : Math.round(remaining * 100);
  const color = percentage !== null && percentage <= 20 ? "bg-red-500" : percentage !== null && percentage <= 50 ? "bg-orange-500" : "bg-emerald-500";
  const value = remaining === null ? loading ? "Loading…" : "Unavailable" : `$${remaining.toFixed(2)} of $1.00 remaining`;
  return <div className="space-y-1">
    <div className="flex flex-wrap items-center justify-between gap-x-3 text-xs"><span className="font-medium">Trial AI budget</span><span className="tabular-nums">{value}{stale && remaining !== null ? " · last check" : ""}</span></div>
    {percentage !== null ? <div role="progressbar" aria-label="Trial AI budget remaining" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percentage} aria-valuetext={value} className="h-2 overflow-hidden rounded-full bg-zinc-900/10 dark:bg-white/10"><div className={`h-full rounded-full transition-[width] ${color}`} style={{ width: `${percentage}%` }} /></div> : <div className="h-2 rounded-full bg-zinc-900/10 dark:bg-white/10" />}
  </div>;
}

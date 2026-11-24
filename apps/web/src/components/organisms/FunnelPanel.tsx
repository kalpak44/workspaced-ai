import { CreditCard, Repeat, Rocket, UserPlus } from "lucide-react";
import { useState } from "react";
import { type FunnelCounts, useFunnel } from "../../hooks/useFunnel";
import { TOUR_STEP, TOUR_STEP_COUNT } from "../../lib/tour";
import { Button, Select, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";
import { MetricTile } from "../molecules/MetricTile";

const PERIODS = [7, 30, 90, 365] as const;
const LAST_STEP = TOUR_STEP_COUNT - 1;

/** "FIND_RECIPE" as "Find recipe", keyed by step number. */
const STEP_NAMES: Record<number, string> = Object.fromEntries(
  Object.entries(TOUR_STEP).map(([key, step]) => [step, key.charAt(0) + key.slice(1).toLowerCase().replaceAll("_", " ")]),
);

/** Of a step's accounts, what share reached the next; a dash when there were none to reach it. */
function share(part: number, whole: number): string {
  return whole === 0 ? "–" : `${Math.round((part / whole) * 100)}%`;
}

function Row({ label, counts }: { readonly label: string; readonly counts: FunnelCounts }) {
  return <tr className="border-t border-zinc-200 dark:border-zinc-800">
    <th scope="row" className="max-w-56 truncate py-2 pr-4 text-left font-medium">{label}</th>
    <td className="px-3 py-2 text-right tabular-nums">{counts.signedUp}</td>
    <td className="px-3 py-2 text-right tabular-nums">{counts.demoStarted}</td>
    <td className="px-3 py-2 text-right tabular-nums">{counts.paid} <span className="text-zinc-500">{share(counts.paid, counts.signedUp)}</span></td>
    <td className="py-2 pl-3 text-right tabular-nums">{counts.retained}/{counts.retentionEligible}</td>
  </tr>;
}

/**
 * The sign-up funnel for admins: of the accounts made in a period, how many started the demo,
 * paid and kept paying, where they came from, and where the guided tour lost them.
 */
export function FunnelPanel() {
  const [days, setDays] = useState<number>(30);
  const { funnel, error, retry } = useFunnel(days);

  if (error) {
    return <div className="card flex flex-wrap items-center justify-between gap-3 p-4">
      <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
      <Button label="Try again" variant="secondary" onClick={retry} />
    </div>;
  }
  if (!funnel) return <div className="flex justify-center p-8"><Spinner label="Loading the funnel" /></div>;

  const { total } = funnel;
  const started = funnel.tour.find((step) => step.step === 0)?.reached ?? 0;
  const finished = funnel.tour.find((step) => step.step === LAST_STEP)?.ended ?? 0;
  const widest = Math.max(1, ...funnel.tour.map((step) => step.reached));

  return <div className="space-y-6">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">Accounts made from {funnel.from} to {funnel.to} (UTC), admins left out.</p>
      <Select aria-label="Period" value={days} onChange={(event) => setDays(Number(event.target.value))}>
        {PERIODS.map((period) => <option key={period} value={period}>Last {period} days</option>)}
      </Select>
    </div>
    <ul aria-label="Funnel" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <MetricTile label="Signed up" icon={UserPlus} value={String(total.signedUp)} />
      <MetricTile label="Started the demo" icon={Rocket} value={String(total.demoStarted)} detail={`${share(total.demoStarted, total.signedUp)} of sign-ups`} />
      <MetricTile label="Paid" icon={CreditCard} value={String(total.paid)} detail={`${share(total.paid, total.signedUp)} of sign-ups`} />
      <MetricTile label="Still paying at 30 days" icon={Repeat} value={String(total.retained)}
        detail={total.retentionEligible === 0 ? "None are 30 days old yet" : `${share(total.retained, total.retentionEligible)} of ${total.retentionEligible} old enough`} />
    </ul>
    <SettingsSection title="By source" description="The utm_source and utm_campaign of the link each sign-up started from. Paying at 30 days counts only accounts old enough.">
      {funnel.sources.length === 0 ? <p className="text-sm text-zinc-500">No sign-ups in this period.</p> :
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-xs text-zinc-500 dark:text-zinc-400">
              <tr><th scope="col" className="pb-2 text-left font-medium">Source</th><th scope="col" className="px-3 pb-2 text-right font-medium">Signed up</th><th scope="col" className="px-3 pb-2 text-right font-medium">Demo</th><th scope="col" className="px-3 pb-2 text-right font-medium">Paid</th><th scope="col" className="pb-2 pl-3 text-right font-medium">Paying at 30 days</th></tr>
            </thead>
            <tbody>
              {funnel.sources.map((row) => <Row key={`${row.source}/${row.campaign}`} counts={row.counts}
                label={row.source === null ? "No source" : row.campaign ? `${row.source} · ${row.campaign}` : row.source} />)}
            </tbody>
          </table>
        </div>}
    </SettingsSection>
    <SettingsSection title="Guided tour" description={`${started} started it, ${finished} finished it (${share(finished, started)}). Each bar is how many reached the step; the note says how many stopped there.`}>
      {funnel.tour.length === 0 ? <p className="text-sm text-zinc-500">No tours in this period.</p> :
        <ol aria-label="Tour steps" className="space-y-1.5">
          {funnel.tour.map((step) => {
            const stopped = [step.paused && `${step.paused} skipped`, step.step !== LAST_STEP && step.ended && `${step.ended} quit`, step.open && `${step.open} still here`].filter(Boolean).join(" · ");
            return <li key={step.step} className="grid grid-cols-[8rem_1fr_auto] items-center gap-3 text-sm">
              <span className="truncate text-zinc-600 dark:text-zinc-300">{step.step + 1}. {STEP_NAMES[step.step] ?? `Step ${step.step}`}</span>
              <span className="h-2 rounded-full bg-zinc-100 dark:bg-zinc-800"><span className="block h-2 rounded-full bg-zinc-900 dark:bg-zinc-100" style={{ width: `${(step.reached / widest) * 100}%` }} /></span>
              <span className="text-right tabular-nums">{step.reached}{stopped && <span className="ml-2 text-xs text-zinc-500">{stopped}</span>}</span>
            </li>;
          })}
        </ol>}
    </SettingsSection>
  </div>;
}

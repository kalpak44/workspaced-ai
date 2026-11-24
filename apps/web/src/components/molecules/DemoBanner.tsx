import { TrialBudgetProgress } from "./TrialBudgetProgress";
import { Clock, Heart } from "lucide-react";
import { useTrialBudget } from "../../hooks/useTrialBudget";
import { useEffect, useState } from "react";

interface Props {
  /** ISO 8601; null while the demo's VM is still starting. */
  readonly expiresAt: string | null;
  readonly name: string;
  /** Opens keeping it as a workspace of the user's own; absent where that is not offered. */
  readonly onKeep?: () => void;
}

/** "3 h 12 min", "8 min", or "less than a minute". */
export function timeLeft(until: number, now: number): string {
  const minutes = Math.floor((until - now) / 60_000);
  if (minutes < 1) return "less than a minute";
  const hours = Math.floor(minutes / 60);
  return hours > 0 ? `${hours} h ${minutes % 60} min` : `${minutes} min`;
}

/**
 * Says on every page, for as long as the demo exists, that it is free and when it ends, and offers
 * to keep it, so nobody loses what they built without having had the choice.
 */
export function DemoBanner({ expiresAt, name, onKeep }: Props) {
  const budget = useTrialBudget();
  const percentage = budget.remaining === null ? null : Math.round(budget.remaining * 100);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const until = expiresAt ? Date.parse(expiresAt) : null;
  const clock = until ? new Date(until).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : null;
  return (
    <div role="status" aria-label="Demo workspace" data-tour="demo-banner"
      className="flex flex-col gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-900 lg:flex-row lg:items-center lg:gap-6 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-200">
      <div className="flex min-w-0 flex-1 items-start gap-2 sm:items-center"><Clock aria-hidden className="mt-0.5 size-4 shrink-0 sm:mt-0" />
      <p className="min-w-0">
        <span className="font-medium">{name} is your free demo.</span>{" "}
        {until && clock
          ? <>It ends in <span className="font-medium tabular-nums">{timeLeft(until, now)}</span>, at {clock}, unless you keep it.</>
          : <>It ends a few hours after it starts, unless you keep it.</>}
      </p>
      {onKeep && <button type="button" onClick={onKeep}
        className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-amber-900 px-3 py-1.5 text-xs font-semibold text-amber-50 hover:bg-amber-800 dark:bg-amber-200 dark:text-amber-950 dark:hover:bg-amber-100">
        <Heart aria-hidden className="size-3.5" />Keep it</button>}
      </div>
      <div className="w-full shrink-0 space-y-1 lg:w-64">
        <TrialBudgetProgress remaining={budget.remaining} loading={budget.loading} stale={budget.error} />
        {budget.error ? <p className="text-xs">Usage unavailable; any displayed budget is from the last check.</p> : percentage === 0 ? <p className="text-xs"><a href="/settings/ai-connectors" className="underline underline-offset-2">Add your own connector</a> to continue using AI.</p> : null}
      </div>
    </div>
  );
}

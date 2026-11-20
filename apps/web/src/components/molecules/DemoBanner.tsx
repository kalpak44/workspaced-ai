import { Clock } from "lucide-react";
import { useEffect, useState } from "react";

interface Props {
  /** ISO 8601; null while the demo's VM is still starting. */
  readonly expiresAt: string | null;
  readonly name: string;
}

/** "3 h 12 min", "8 min", or "less than a minute". */
export function timeLeft(until: number, now: number): string {
  const minutes = Math.floor((until - now) / 60_000);
  if (minutes < 1) return "less than a minute";
  const hours = Math.floor(minutes / 60);
  return hours > 0 ? `${hours} h ${minutes % 60} min` : `${minutes} min`;
}

/**
 * Says on every page, for as long as the demo exists, that it is free and when it is deleted, so
 * nobody builds something they expect to keep in it.
 */
export function DemoBanner({ expiresAt, name }: Props) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const until = expiresAt ? Date.parse(expiresAt) : null;
  const clock = until ? new Date(until).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : null;
  return (
    <div role="status" aria-label="Demo workspace" data-tour="demo-banner"
      className="flex items-start gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-900 sm:items-center dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-200">
      <Clock aria-hidden className="mt-0.5 size-4 shrink-0 sm:mt-0" />
      <p className="min-w-0">
        <span className="font-medium">{name} is a free demo.</span>{" "}
        {until && clock
          ? <>It is deleted in <span className="font-medium tabular-nums">{timeLeft(until, now)}</span>, at {clock}, with everything in it.</>
          : <>It is deleted a few hours after it starts, with everything in it.</>}
      </p>
    </div>
  );
}

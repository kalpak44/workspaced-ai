import { RotateCw, TriangleAlert } from "lucide-react";
import { Button } from "../atoms";

interface Props {
  /** While the workspace is already starting, there is nothing to restart. */
  readonly restartDisabled: boolean;
  readonly onRestart: () => void;
}

/** Shown wherever a setting applies only from the workspace's next start, until it restarts. */
export function RestartRequiredBanner({ restartDisabled, onRestart }: Props) {
  return (
    <div
      role="status"
      aria-label="Restart required"
      className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 sm:flex-row sm:items-center dark:border-amber-950 dark:bg-amber-950/30"
    >
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-400">
        <TriangleAlert aria-hidden className="size-4.5" />
      </span>
      <p className="min-w-0 flex-1 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
        Settings changed. The workspace picks them up only after it restarts.
      </p>
      <Button tourId="restart-workspace" label="Restart workspace" variant="secondary" icon={RotateCw} disabled={restartDisabled} onClick={onRestart} />
    </div>
  );
}

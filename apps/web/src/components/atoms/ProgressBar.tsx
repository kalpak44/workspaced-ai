interface Props {
  readonly label: string;
  /** 0 to 100. */
  readonly value: number;
  /** Green once the work it measures is done. */
  readonly done?: boolean;
}

/** One bar for every long-running job, so a backup, an upload and a restore read alike. */
export function ProgressBar({ label, value, done = false }: Props) {
  return (
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={value}
      className="h-2 overflow-hidden rounded-full bg-zinc-200 dark:bg-zinc-800">
      <div className={`h-full rounded-full transition-[width] duration-500 ${done ? "bg-emerald-500" : "bg-zinc-900 dark:bg-white"}`} style={{ width: `${value}%` }} />
    </div>
  );
}

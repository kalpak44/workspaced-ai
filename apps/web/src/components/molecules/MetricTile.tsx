import type { LucideIcon } from "lucide-react";

/** A shared summary tile for workspace monitoring and AI-provider usage. */
export function MetricTile({ label, icon: Icon, value, detail }: {
  readonly label: string;
  readonly icon: LucideIcon;
  readonly value: string;
  readonly detail?: string | null;
}) {
  return <li className="card flex min-w-0 flex-col gap-1 p-4">
    <span className="flex items-center gap-2 text-xs font-medium text-zinc-500 dark:text-zinc-400">
      <span className="flex size-6 items-center justify-center rounded-md bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"><Icon aria-hidden className="size-3.5" /></span>{label}
    </span>
    <span className="mt-1 truncate text-xl font-semibold tracking-tight tabular-nums sm:text-2xl">{value}</span>
    {detail && <span className="truncate text-xs text-zinc-500 dark:text-zinc-400">{detail}</span>}
  </li>;
}

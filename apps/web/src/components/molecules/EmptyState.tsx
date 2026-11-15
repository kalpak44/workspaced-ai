import { type LucideIcon, Plus } from "lucide-react";
import { Button, Spinner } from "../atoms";

interface Props {
  readonly title: string;
  readonly hint: string;
  readonly busy?: boolean;
  /** Drawn in a soft circle above the title; a spinner takes its place while busy. */
  readonly icon?: LucideIcon;
  readonly action?: { readonly label: string; readonly onClick: () => void; readonly icon?: LucideIcon };
  /** A quieter way out beside the action, e.g. clearing the filters that emptied the list. */
  readonly secondaryAction?: { readonly label: string; readonly onClick: () => void };
}

export function EmptyState({ title, hint, busy = false, icon: Icon, action, secondaryAction }: Props) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      {busy ? (
        <Spinner className="mb-2 size-7 text-zinc-400" />
      ) : (
        Icon && (
          <span className="mb-2 flex size-12 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 ring-8 ring-zinc-50 dark:bg-zinc-800 dark:text-zinc-400 dark:ring-zinc-900/60">
            <Icon aria-hidden className="size-5" />
          </span>
        )
      )}
      <h3 className="text-lg font-semibold tracking-tight">{title}</h3>
      <p className="max-w-sm text-sm text-pretty text-zinc-500 dark:text-zinc-400">{hint}</p>
      {(action || secondaryAction) && (
        <div className="mt-4 flex flex-wrap justify-center gap-2">
          {secondaryAction && <Button label={secondaryAction.label} variant="secondary" onClick={secondaryAction.onClick} />}
          {action && <Button label={action.label} icon={action.icon ?? Plus} onClick={action.onClick} />}
        </div>
      )}
    </div>
  );
}

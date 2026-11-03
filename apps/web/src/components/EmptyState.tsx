import { LoaderCircle, Plus } from "lucide-react";

interface Props {
  readonly title: string;
  readonly hint: string;
  readonly busy?: boolean;
  readonly action?: { readonly label: string; readonly onClick: () => void };
}

export function EmptyState({ title, hint, busy = false, action }: Props) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
      {busy && <LoaderCircle aria-hidden className="mb-2 size-8 animate-spin text-zinc-400" />}
      <h3 className="text-2xl font-semibold">{title}</h3>
      <p className="text-sm text-zinc-500 dark:text-zinc-400">{hint}</p>
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          className="mt-6 flex items-center gap-2 rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200"
        >
          <Plus className="size-4" />
          {action.label}
        </button>
      )}
    </div>
  );
}

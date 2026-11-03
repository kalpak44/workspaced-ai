import type { LucideIcon } from "lucide-react";

interface Props {
  readonly label: string;
  readonly onClick: () => void;
  readonly icon?: LucideIcon;
}

/** The primary call to action; inverted against the page in both themes. */
export function Button({ label, onClick, icon: Icon }: Props) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex items-center gap-2 rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200"
    >
      {Icon && <Icon className="size-4" />}
      {label}
    </button>
  );
}

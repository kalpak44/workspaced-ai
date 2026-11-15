import type { LucideIcon } from "lucide-react";
import { Spinner } from "./Spinner";

interface Props {
  readonly label: string;
  readonly onClick?: () => void;
  readonly icon?: LucideIcon;
  /** Primary is inverted against the page; secondary is a quiet outline. */
  readonly variant?: "primary" | "secondary" | "danger";
  readonly type?: "button" | "submit";
  readonly disabled?: boolean;
  readonly autoFocus?: boolean;
  /** Shows a spinner in place of the icon and blocks clicks while work is in flight. */
  readonly busy?: boolean;
}

const VARIANTS = {
  primary:
    "bg-zinc-900 text-white shadow-sm hover:bg-zinc-800 active:bg-zinc-950 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200 dark:active:bg-zinc-300",
  secondary:
    "border border-zinc-300 bg-white text-zinc-700 shadow-xs hover:border-zinc-400 hover:bg-zinc-50 active:bg-zinc-100 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:border-zinc-600 dark:hover:bg-zinc-800",
  danger:
    "bg-rose-600 text-white shadow-sm hover:bg-rose-700 active:bg-rose-800 dark:bg-rose-600 dark:hover:bg-rose-500",
};

export function Button({
  label,
  onClick,
  icon: Icon,
  variant = "primary",
  type = "button",
  disabled = false,
  autoFocus = false,
  busy = false,
}: Props) {
  return (
    <button
      // A literal per branch: the button type is never computed from a prop at render time.
      type={type === "submit" ? "submit" : "button"}
      onClick={onClick}
      disabled={disabled || busy}
      autoFocus={autoFocus}
      aria-busy={busy}
      className={`inline-flex min-h-9 items-center justify-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium whitespace-nowrap transition-colors select-none disabled:pointer-events-none disabled:opacity-50 ${VARIANTS[variant]}`}
    >
      {busy && <Spinner />}
      {!busy && Icon && <Icon className="size-4" />}
      {label}
    </button>
  );
}

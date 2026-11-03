import type { LucideIcon } from "lucide-react";
import { Spinner } from "./Spinner";

interface Props {
  readonly label: string;
  readonly onClick?: () => void;
  readonly icon?: LucideIcon;
  /** Primary is inverted against the page; secondary is a quiet outline. */
  readonly variant?: "primary" | "secondary";
  readonly type?: "button" | "submit";
  readonly disabled?: boolean;
  /** Shows a spinner in place of the icon and blocks clicks while work is in flight. */
  readonly busy?: boolean;
}

const VARIANTS = {
  primary:
    "bg-zinc-900 text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200",
  secondary:
    "border border-zinc-300 hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800",
};

export function Button({
  label,
  onClick,
  icon: Icon,
  variant = "primary",
  type = "button",
  disabled = false,
  busy = false,
}: Props) {
  return (
    <button
      // A literal per branch: the button type is never computed from a prop at render time.
      type={type === "submit" ? "submit" : "button"}
      onClick={onClick}
      disabled={disabled || busy}
      aria-busy={busy}
      className={`flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]}`}
    >
      {busy && <Spinner />}
      {!busy && Icon && <Icon className="size-4" />}
      {label}
    </button>
  );
}

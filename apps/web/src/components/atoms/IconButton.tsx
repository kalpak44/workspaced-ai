import type { LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes } from "react";

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type" | "children"> {
  /** Read by screen readers and tests; the button shows only the icon. */
  readonly label: string;
  readonly icon: LucideIcon;
  /** Submits its form instead of being a plain button. */
  readonly submit?: boolean;
}

export function IconButton({
  label,
  icon: Icon,
  submit = false,
  className = "",
  ...rest
}: Readonly<Props>) {
  return (
    <button
      // A literal per branch: the button type is never computed from a prop at render time.
      type={submit ? "submit" : "button"}
      aria-label={label}
      className={`rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 disabled:cursor-not-allowed disabled:opacity-40 dark:hover:bg-zinc-800 dark:hover:text-zinc-100 ${className}`}
      {...rest}
    >
      <Icon className="size-4" />
    </button>
  );
}

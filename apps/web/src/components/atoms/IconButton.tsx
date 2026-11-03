import type { LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes } from "react";

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type" | "children"> {
  /** Read by screen readers and tests; the button shows only the icon. */
  readonly label: string;
  readonly icon: LucideIcon;
}

export function IconButton({ label, icon: Icon, className = "", ...rest }: Readonly<Props>) {
  return (
    <button
      type="button"
      aria-label={label}
      className={`rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100 ${className}`}
      {...rest}
    >
      <Icon className="size-4" />
    </button>
  );
}

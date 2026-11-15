import { ChevronDown } from "lucide-react";
import type { ComponentProps } from "react";

interface Props extends Omit<ComponentProps<"select">, "className"> {
  /** Width and spacing from the caller; the frame and the chevron are the select's own. */
  readonly className?: string;
}

/** A native select in the shared field frame; native keeps the platform picker on phones. */
export function Select({ className = "", children, ...rest }: Readonly<Props>) {
  return (
    <span className={`relative inline-flex min-w-0 ${className}`}>
      <select {...rest} className="field min-h-9 w-full min-w-0 cursor-pointer appearance-none py-1.5 pr-8 pl-3 text-sm disabled:cursor-not-allowed disabled:opacity-60">
        {children}
      </select>
      <ChevronDown aria-hidden className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-zinc-400" />
    </span>
  );
}

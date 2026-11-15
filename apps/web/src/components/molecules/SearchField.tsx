import { Search, X } from "lucide-react";
import { type ComponentProps, useRef } from "react";

interface Props extends Omit<ComponentProps<"input">, "className" | "type" | "value" | "onChange"> {
  /** Names the field for readers; the placeholder is only a hint. */
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  /** Width and spacing from the caller. */
  readonly className?: string;
}

/** A search box with its icon and a clear button once something is typed. */
export function SearchField({ label, value, onChange, className = "", ...rest }: Readonly<Props>) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <label className={`field flex min-w-0 items-center gap-2 px-3 focus-within:border-zinc-500 focus-within:ring-3 focus-within:ring-zinc-900/10 dark:focus-within:border-zinc-400 dark:focus-within:ring-white/10 ${className}`}>
      <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
      <input ref={input} type="search" aria-label={label} value={value} onChange={(event) => onChange(event.target.value)}
        className="min-h-9 w-full min-w-0 bg-transparent text-sm outline-none placeholder:text-zinc-400 [&::-webkit-search-cancel-button]:hidden" {...rest} />
      {value !== "" && (
        <button type="button" aria-label="Clear search" title="Clear search" onClick={() => { onChange(""); input.current?.focus(); }}
          className="-mr-1 inline-flex size-6 shrink-0 items-center justify-center rounded text-zinc-400 hover:bg-zinc-100 hover:text-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-zinc-200">
          <X aria-hidden className="size-3.5" />
        </button>
      )}
    </label>
  );
}

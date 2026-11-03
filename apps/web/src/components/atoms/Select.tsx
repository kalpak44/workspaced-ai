import type { ComponentProps } from "react";

interface Option {
  readonly value: string;
  readonly label: string;
}

interface Props extends Omit<ComponentProps<"select">, "className" | "children"> {
  readonly label: string;
  readonly options: readonly Option[];
  /** Shown, and unselectable, until a real option is picked. */
  readonly placeholder: string;
}

/** A labelled native select, styled to sit beside TextField. */
export function Select({ label, id, options, placeholder, ...rest }: Readonly<Props>) {
  return (
    <label htmlFor={id} className="flex flex-col gap-1.5 text-sm">
      <span className="text-zinc-500 dark:text-zinc-400">{label}</span>
      <select
        id={id}
        className="rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none focus:border-zinc-500 dark:border-zinc-600 dark:focus:border-zinc-400"
        {...rest}
      >
        <option value="" disabled>
          {placeholder}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

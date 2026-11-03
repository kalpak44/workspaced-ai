import type { ComponentProps } from "react";

// ComponentProps carries `ref` as well, which React 19 passes to a function component.
interface Props extends Omit<ComponentProps<"input">, "className"> {
  readonly label: string;
}

/** A labelled single-line input; the label is visible and names the field for readers. */
export function TextField({ label, id, ...rest }: Props) {
  return (
    <label htmlFor={id} className="flex flex-col gap-1.5 text-sm">
      <span className="text-zinc-500 dark:text-zinc-400">{label}</span>
      <input
        id={id}
        className="rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none focus:border-zinc-500 dark:border-zinc-600 dark:focus:border-zinc-400"
        {...rest}
      />
    </label>
  );
}

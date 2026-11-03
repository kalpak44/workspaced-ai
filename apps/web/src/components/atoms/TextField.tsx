import type { ComponentProps } from "react";

// ComponentProps carries `ref` as well, which React 19 passes to a function component.
interface Props extends Omit<ComponentProps<"input">, "className"> {
  readonly label: string;
  /** Shown under the field and read out with it; also marks the field invalid. */
  readonly error?: string | null;
}

/**
 * A labelled single-line input; the label is visible and names the field for readers. The
 * error sits outside the label, so it describes the field without changing its name.
 */
export function TextField({ label, id, error, ...rest }: Readonly<Props>) {
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <label htmlFor={id} className="text-zinc-500 dark:text-zinc-400">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className="rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none focus:border-zinc-500 aria-invalid:border-rose-500 dark:border-zinc-600 dark:focus:border-zinc-400"
        {...rest}
      />
      {error && (
        <span id={errorId} className="text-rose-600 dark:text-rose-400">
          {error}
        </span>
      )}
    </div>
  );
}

import type { ComponentProps } from "react";

// ComponentProps carries `ref` as well, which React 19 passes to a function component.
interface Props extends Omit<ComponentProps<"input">, "className"> {
  readonly label: string;
  /** Shown under the field and read out with it; also marks the field invalid. */
  readonly error?: string | null;
  /** Guidance under the field; an error takes its place. */
  readonly hint?: string;
  /** Kept for callers; every field now shares one solid frame. */
  readonly surface?: "transparent" | "solid";
}

/**
 * A labelled single-line input; the label is visible and names the field for readers. The
 * error sits outside the label, so it describes the field without changing its name.
 */
export function TextField({ label, id, error, hint, surface: _surface, ...rest }: Readonly<Props>) {
  const noteId = `${id}-note`;
  const note = error || hint;
  return (
    <div className="flex min-w-0 flex-col gap-1.5 text-sm">
      <label htmlFor={id} className="font-medium text-zinc-700 dark:text-zinc-300">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={note ? noteId : undefined}
        className={`field min-h-9 w-full min-w-0 px-3 py-2 disabled:cursor-not-allowed disabled:opacity-60 aria-invalid:border-rose-500 aria-invalid:focus:ring-rose-500/15`}
        {...rest}
      />
      {note && (
        <span
          id={noteId}
          className={
            error ? "text-xs text-rose-600 dark:text-rose-400" : "text-xs text-zinc-500 dark:text-zinc-400"
          }
        >
          {note}
        </span>
      )}
    </div>
  );
}

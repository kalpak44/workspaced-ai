interface Option<T extends string> {
  readonly value: T;
  readonly label: string;
}

interface Props<T extends string> {
  /** Names the group for readers, e.g. "Category". */
  readonly label: string;
  /** Shown first and chosen when nothing else is, e.g. "All". */
  readonly allLabel: string;
  readonly options: readonly Option<T>[];
  readonly value: T | null;
  /** Null for the "all" chip; choosing the active chip again clears it. */
  readonly onChange: (value: T | null) => void;
}

const CHIP =
  "shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium whitespace-nowrap transition-colors";
const ON = "border-zinc-900 bg-zinc-900 text-white shadow-sm dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900";
const OFF =
  "border-zinc-200 bg-white text-zinc-600 hover:border-zinc-400 hover:text-zinc-900 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300 dark:hover:border-zinc-600 dark:hover:text-white";

/** A row of toggle chips that narrows a list; it scrolls sideways on a phone instead of wrapping. */
export function FilterChips<T extends string>({ label, allLabel, options, value, onChange }: Props<T>) {
  return (
    <div role="group" aria-label={label}
      className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:px-0 [&::-webkit-scrollbar]:hidden">
      <button type="button" aria-pressed={value === null} onClick={() => onChange(null)} className={`${CHIP} ${value === null ? ON : OFF}`}>
        {allLabel}
      </button>
      {options.map((option) => (
        <button key={option.value} type="button" aria-pressed={value === option.value}
          onClick={() => onChange(value === option.value ? null : option.value)}
          className={`${CHIP} ${value === option.value ? ON : OFF}`}>
          {option.label}
        </button>
      ))}
    </div>
  );
}

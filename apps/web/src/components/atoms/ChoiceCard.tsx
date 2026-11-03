import { Check, type LucideIcon } from "lucide-react";

interface Props {
  /** Shared by every card in the group, which is what makes them one radio group. */
  readonly name: string;
  readonly value: string;
  readonly label: string;
  /** A muted second line, e.g. where the choice points. */
  readonly detail?: string;
  readonly icon: LucideIcon;
  readonly checked: boolean;
  readonly disabled?: boolean;
  readonly onChange: (value: string) => void;
}

/**
 * A radio button drawn as a card. The input stays in the page, visually hidden, so keyboard
 * and screen readers treat the group exactly as native radios.
 */
export function ChoiceCard({
  name,
  value,
  label,
  detail,
  icon: Icon,
  checked,
  disabled = false,
  onChange,
}: Props) {
  return (
    <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-zinc-200 px-4 py-3 hover:border-zinc-400 has-checked:border-zinc-900 has-checked:bg-zinc-50 has-focus-visible:ring-2 has-focus-visible:ring-zinc-400 has-disabled:cursor-not-allowed has-disabled:opacity-60 dark:border-zinc-700 dark:hover:border-zinc-500 dark:has-checked:border-zinc-200 dark:has-checked:bg-zinc-800/60">
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        disabled={disabled}
        onChange={() => onChange(value)}
        className="sr-only"
      />
      <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200">
        <Icon aria-hidden className="size-4" />
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="text-sm font-medium">{label}</span>
        {detail && (
          <span className="truncate font-mono text-xs text-zinc-500 dark:text-zinc-400">
            {detail}
          </span>
        )}
      </span>
      <span
        aria-hidden
        className={`flex size-5 shrink-0 items-center justify-center rounded-full border ${
          checked
            ? "border-zinc-900 bg-zinc-900 text-white dark:border-white dark:bg-white dark:text-zinc-900"
            : "border-zinc-300 dark:border-zinc-600"
        }`}
      >
        {checked && <Check className="size-3" strokeWidth={3} />}
      </span>
    </label>
  );
}

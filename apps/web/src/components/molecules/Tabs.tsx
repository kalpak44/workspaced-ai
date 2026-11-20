import type { LucideIcon } from "lucide-react";

interface Tab<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly icon?: LucideIcon;
}

interface Props<T extends string> {
  readonly label: string;
  readonly tabs: readonly Tab<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
}

/** A segmented switch between views of one page. */
export function Tabs<T extends string>({ label, tabs, value, onChange }: Props<T>) {
  return (
    <div role="tablist" aria-label={label} className="inline-flex max-w-full overflow-x-auto rounded-lg bg-zinc-100 p-1 [scrollbar-width:none] dark:bg-zinc-800/70 [&::-webkit-scrollbar]:hidden">
      {tabs.map(({ value: tab, label: name, icon: Icon }) => (
        <button
          key={tab}
          data-tab={tab}
          type="button"
          role="tab"
          aria-selected={tab === value}
          onClick={() => onChange(tab)}
          className={`flex shrink-0 items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors ${
            tab === value
              ? "bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-900/5 dark:bg-zinc-950 dark:text-zinc-100 dark:ring-white/10"
              : "text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
          }`}
        >
          {Icon && <Icon aria-hidden className="size-4" />}
          {name}
        </button>
      ))}
    </div>
  );
}

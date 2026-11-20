import type { LucideIcon } from "lucide-react";

interface Section<T extends string> {
  readonly id: T;
  readonly label: string;
  readonly Icon: LucideIcon;
}

interface Props<T extends string> {
  /** Names the navigation landmark. */
  readonly label: string;
  readonly sections: readonly Section<T>[];
  readonly current: T;
  readonly onSelect: (id: T) => void;

}

/**
 * A settings page's own sections: a vertical list beside the page on wider screens, a strip of
 * tabs that scrolls sideways on a phone, so the page itself is never pushed below the fold.
 */
export function SectionNav<T extends string>({ label, sections, current, onSelect }: Props<T>) {
  return (
    <>
      <nav aria-label={label}
        className="flex gap-1 overflow-x-auto px-3 py-2 [scrollbar-width:none] sm:flex-col sm:overflow-visible sm:p-3 [&::-webkit-scrollbar]:hidden">
        {sections.map(({ id, label: name, Icon }) => (
          <button data-section={id} key={id} type="button" aria-current={current === id ? "page" : undefined} onClick={() => onSelect(id)}
            className={`flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-left text-sm font-medium whitespace-nowrap transition-colors ${
              current === id
                ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100"
                : "text-zinc-500 hover:bg-zinc-100/70 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800/60 dark:hover:text-zinc-100"
            }`}>
            <Icon aria-hidden className="size-4 shrink-0" />
            {name}
          </button>
        ))}
      </nav>
    </>
  );
}

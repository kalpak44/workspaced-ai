import { ChevronLeft, ChevronRight } from "lucide-react";

interface Props {
  readonly page: number;
  readonly pageCount: number;
  readonly onChange: (page: number) => void;
  /** Names the navigation landmark, e.g. "Session pages". */
  readonly label: string;
  /** What one item is called; names the buttons, e.g. "Next session page". */
  readonly noun: string;
  /** One-based range shown and the total, for the "1–10 of 42" summary. */
  readonly range?: { readonly first: number; readonly last: number; readonly total: number };
  readonly disabled?: boolean;
}

const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

/**
 * The page numbers to draw: always the first and last, the current one with a neighbour each
 * side, and a gap wherever pages are skipped, so the bar stays the same width on any count.
 */
export function pageWindow(page: number, pageCount: number): ReadonlyArray<number | "gap"> {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, index) => index + 1);
  const from = Math.max(2, Math.min(page - 1, pageCount - 4));
  const to = Math.min(pageCount - 1, Math.max(page + 1, 5));
  const middle = Array.from({ length: to - from + 1 }, (_, index) => from + index);
  return [1, ...(from > 2 ? ["gap" as const] : []), ...middle, ...(to < pageCount - 1 ? ["gap" as const] : []), pageCount];
}

const STEP =
  "inline-flex size-9 items-center justify-center rounded-lg border border-zinc-200 bg-white text-zinc-600 shadow-xs transition-colors hover:border-zinc-300 hover:bg-zinc-50 hover:text-zinc-900 disabled:pointer-events-none disabled:opacity-40 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300 dark:hover:border-zinc-700 dark:hover:bg-zinc-800 dark:hover:text-white";
const NUMBER =
  "inline-flex h-9 min-w-9 items-center justify-center rounded-lg px-2 text-sm font-medium tabular-nums text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 aria-current:bg-zinc-900 aria-current:text-white aria-current:shadow-sm dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white dark:aria-current:bg-white dark:aria-current:text-zinc-900";

/**
 * Page controls under a list. Phones get Previous, "Page 2 of 5" and Next; wider screens add
 * the page numbers. Renders nothing for a single page, which needs no controls.
 */
export function Pagination({ page, pageCount, onChange, label, noun, range, disabled = false }: Props) {
  if (pageCount <= 1) return null;
  const go = (next: number) => onChange(Math.max(1, Math.min(pageCount, next)));
  return (
    <nav aria-label={label} className="flex flex-wrap items-center justify-between gap-3 pt-1">
      <p className="text-xs text-zinc-500 tabular-nums dark:text-zinc-400">
        {range ? <>{range.first}–{range.last} of {range.total}<span className="mx-1.5 text-zinc-300 dark:text-zinc-700">·</span></> : null}
        <span>Page {page} of {pageCount}</span>
      </p>
      <div className="flex items-center gap-1">
        <button type="button" aria-label={`Previous ${noun} page`} title="Previous page" disabled={disabled || page === 1} onClick={() => go(page - 1)} className={STEP}>
          <ChevronLeft aria-hidden className="size-4" />
        </button>
        <span className="hidden items-center gap-1 sm:flex">
          {pageWindow(page, pageCount).map((entry, index) =>
            entry === "gap" ? (
              <span key={`gap-${index}`} aria-hidden className="inline-flex h-9 min-w-6 items-center justify-center text-sm text-zinc-400">…</span>
            ) : (
              <button key={entry} type="button" aria-label={`${capitalise(noun)} page ${entry}`} aria-current={entry === page ? "page" : undefined}
                disabled={disabled} onClick={() => go(entry)} className={NUMBER}>
                {entry}
              </button>
            ),
          )}
        </span>
        <button type="button" aria-label={`Next ${noun} page`} title="Next page" disabled={disabled || page === pageCount} onClick={() => go(page + 1)} className={STEP}>
          <ChevronRight aria-hidden className="size-4" />
        </button>
      </div>
    </nav>
  );
}

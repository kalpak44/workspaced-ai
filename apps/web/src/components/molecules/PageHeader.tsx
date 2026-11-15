import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";

interface Crumb {
  readonly label: string;
  readonly onClick: () => void;
}

interface Props {
  readonly title: string;
  /** The pages above this one, outermost first; each goes back to its page. */
  readonly crumbs?: readonly Crumb[];
  /** Beside the title, e.g. a status. */
  readonly badge?: ReactNode;
  readonly actions?: ReactNode;
  /** One line under a page title saying what the page is for. */
  readonly description?: ReactNode;
  /**
   * "page" opens a page with a large title in line with its content; "bar" is a slim sticky
   * bar for tool views such as a chat or a terminal, and for detail pages titled in their body.
   */
  readonly variant?: "page" | "bar";
  /** The content's max width, so the title lines up with what is under it. */
  readonly width?: string;
}

function Crumbs({ crumbs }: { readonly crumbs: readonly Crumb[] }) {
  return (
    <nav aria-label="Breadcrumb" className="flex min-w-0 max-w-[50%] shrink-0 items-center gap-1.5">
      {crumbs.map((crumb) => (
        <span key={crumb.label} className="flex min-w-0 items-center gap-1.5">
          <button type="button" onClick={crumb.onClick}
            className="truncate rounded text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100">
            {crumb.label}
          </button>
          <ChevronRight aria-hidden className="size-3.5 shrink-0 text-zinc-400" />
        </span>
      ))}
    </nav>
  );
}

export function PageHeader({ title, crumbs = [], badge, actions, description, variant = "page", width = "max-w-5xl" }: Props) {
  if (variant === "bar") {
    return (
      <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-zinc-200 bg-white/85 px-4 backdrop-blur-md supports-[backdrop-filter]:bg-white/70 sm:px-6 dark:border-zinc-800 dark:bg-zinc-950/85 dark:supports-[backdrop-filter]:bg-zinc-950/70">
        <div className="flex min-w-0 flex-1 items-center gap-1.5 text-sm">
          {crumbs.length > 0 && <Crumbs crumbs={crumbs} />}
          <h1 className="truncate font-semibold tracking-tight">{title}</h1>
          {badge}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </header>
    );
  }
  return (
    <header className={`mx-auto flex w-full shrink-0 flex-col gap-2 px-4 pt-6 sm:px-6 sm:pt-8 ${width}`}>
      {crumbs.length > 0 && <div className="flex text-sm"><Crumbs crumbs={crumbs} /></div>}
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <h1 className="truncate text-2xl font-semibold tracking-tight">{title}</h1>
            {badge}
          </div>
          {description && <p className="mt-1 max-w-2xl text-sm text-pretty text-zinc-500 dark:text-zinc-400">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}

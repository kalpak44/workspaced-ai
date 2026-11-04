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
}

export function PageHeader({ title, crumbs = [], badge, actions }: Props) {
  return (
    <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-zinc-200 px-4 sm:px-6 dark:border-zinc-800">
      <div className="flex min-w-0 flex-1 items-center gap-1.5 text-sm">
        {crumbs.length > 0 && (
          <nav aria-label="Breadcrumb" className="flex min-w-0 max-w-[45%] items-center gap-1.5">
            {crumbs.map((crumb) => (
              <span key={crumb.label} className="flex min-w-0 max-w-[45%] items-center gap-1.5">
                <button
                  type="button"
                  onClick={crumb.onClick}
                  className="truncate text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
                >
                  {crumb.label}
                </button>
                <ChevronRight aria-hidden className="size-3.5 shrink-0 text-zinc-400" />
              </span>
            ))}
          </nav>
        )}
        <h1 className="truncate font-medium">{title}</h1>
        {badge}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </header>
  );
}

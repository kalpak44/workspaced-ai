import type { ReactNode } from "react";

interface Props {
  readonly guided?: boolean;
  readonly header: ReactNode;
  readonly sidebar?: ReactNode;
  /** Shown above the page, e.g. an error banner. */
  readonly notice?: ReactNode;
  /** The page, which brings its own header. */
  readonly children: ReactNode;
}

/** The frame every page sits in: a bar across the top, a sidebar, and the page beside it. */
export function AppLayout({ header, sidebar, notice, children, guided = false }: Props) {
  return (
    <div className="flex flex-col pr-[env(safe-area-inset-right)] pl-[env(safe-area-inset-left)]"
      style={guided ? { height: "calc(100dvh - var(--tour-header, 160px) - var(--tour-footer, 80px))", marginTop: "var(--tour-header, 160px)" } : { height: "100dvh" }}>
      {/* The first stop for a keyboard: past the bar and the sidebar, straight to the page. */}
      <a href="#main" className="sr-only z-50 rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed focus:top-2 focus:left-2 dark:bg-white dark:text-zinc-900">
        Skip to content
      </a>
      {header}
      <div className="flex min-h-0 flex-1 flex-col sm:flex-row">
        {sidebar}
        <main id="main" tabIndex={-1} className="flex min-w-0 flex-1 flex-col overflow-y-auto overscroll-contain outline-none">
          {notice}
          {children}
        </main>
      </div>
    </div>
  );
}

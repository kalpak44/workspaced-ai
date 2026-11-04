import type { ReactNode } from "react";

/** A page's own navigation, separate from workspace management. */
export function ContextLayout({ sidebar, children, compactSidebar = false }: { readonly sidebar: ReactNode; readonly children: ReactNode; readonly compactSidebar?: boolean }) {
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col sm:flex-row">
      <aside className={`flex min-h-0 shrink-0 flex-col border-b border-zinc-200 sm:max-h-none sm:w-56 sm:overflow-visible lg:w-64 sm:border-r sm:border-b-0 dark:border-zinc-800 ${compactSidebar ? "relative z-10" : "max-h-[40dvh] overflow-y-auto"}`}>
        {sidebar}
      </aside>
      <section className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto">{children}</section>
    </div>
  );
}

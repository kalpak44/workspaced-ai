import type { ReactNode } from "react";

/** A page's own navigation, separate from workspace management. */
export function ContextLayout({ sidebar, children }: { readonly sidebar: ReactNode; readonly children: ReactNode }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col sm:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-zinc-200 sm:w-64 sm:border-r sm:border-b-0 dark:border-zinc-800">
        {sidebar}
      </aside>
      <section className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto">{children}</section>
    </div>
  );
}

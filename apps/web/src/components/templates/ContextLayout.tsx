import type { ReactNode } from "react";

interface Props {
  readonly sidebar: ReactNode;
  readonly children: ReactNode;
  readonly compactSidebar?: boolean;
  /**
   * Keeps the navigation as a column inside the shared page width, so the page lines up with
   * every other one; without it the navigation is a full-height sidebar, as a chat wants.
   */
  readonly contained?: boolean;
}

/** A page's own navigation, separate from workspace management. */
export function ContextLayout({ sidebar, children, compactSidebar = false, contained = false }: Props) {
  if (contained) {
    return (
      <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col gap-4 px-4 pt-6 pb-10 sm:flex-row sm:gap-8 sm:px-6">
        <aside className="-mx-4 flex min-w-0 shrink-0 flex-col sm:mx-0 sm:w-48 sm:[&_nav]:px-0 sm:[&_nav]:pt-0">{sidebar}</aside>
        <section className="flex min-h-0 min-w-0 flex-1 flex-col">{children}</section>
      </div>
    );
  }
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col sm:flex-row">
      <aside className={`flex min-h-0 shrink-0 flex-col border-b border-zinc-200 bg-white sm:max-h-none sm:w-56 sm:overflow-visible lg:w-64 sm:border-r sm:border-b-0 dark:border-zinc-800 dark:bg-zinc-950 ${compactSidebar ? "relative z-10" : "max-h-[40dvh] overflow-y-auto"}`}>
        {sidebar}
      </aside>
      <section className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto">{children}</section>
    </div>
  );
}

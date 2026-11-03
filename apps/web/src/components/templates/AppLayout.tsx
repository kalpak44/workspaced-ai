import type { ReactNode } from "react";

interface Props {
  readonly header: ReactNode;
  readonly sidebar: ReactNode;
  readonly title: string;
  /** Controls on the right of the title row. */
  readonly actions?: ReactNode;
  /** Shown above the title, e.g. an error banner. */
  readonly notice?: ReactNode;
  readonly children: ReactNode;
}

/** The page frame: a bar across the top, a sidebar, and a titled main area. */
export function AppLayout({ header, sidebar, title, actions, notice, children }: Props) {
  return (
    <div className="flex h-screen flex-col">
      {header}
      <div className="flex min-h-0 flex-1">
        {sidebar}
        <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
          {notice}
          <div className="flex items-center justify-between gap-2 px-4 pt-4 pb-2">
            <h1 className="truncate font-medium">{title}</h1>
            {actions}
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}

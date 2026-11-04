import type { ReactNode } from "react";

interface Props {
  readonly header: ReactNode;
  readonly sidebar?: ReactNode;
  /** Shown above the page, e.g. an error banner. */
  readonly notice?: ReactNode;
  /** The page, which brings its own header. */
  readonly children: ReactNode;
}

/** The frame every page sits in: a bar across the top, a sidebar, and the page beside it. */
export function AppLayout({ header, sidebar, notice, children }: Props) {
  return (
    <div className="flex h-screen flex-col">
      {header}
      <div className="flex min-h-0 flex-1">
        {sidebar}
        <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
          {notice}
          {children}
        </main>
      </div>
    </div>
  );
}

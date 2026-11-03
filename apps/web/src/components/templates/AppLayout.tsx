import type { ReactNode } from "react";

interface Props {
  readonly header: ReactNode;
  readonly sidebar: ReactNode;
  readonly title: string;
  /** Shown above the title, e.g. an error banner. */
  readonly notice?: ReactNode;
  readonly children: ReactNode;
}

/** The page frame: a bar across the top, a sidebar, and a titled main area. */
export function AppLayout({ header, sidebar, title, notice, children }: Props) {
  return (
    <div className="flex h-screen flex-col">
      {header}
      <div className="flex min-h-0 flex-1">
        {sidebar}
        <main className="flex flex-1 flex-col">
          {notice}
          <h1 className="px-4 pt-4 pb-2 font-medium">{title}</h1>
          {children}
        </main>
      </div>
    </div>
  );
}

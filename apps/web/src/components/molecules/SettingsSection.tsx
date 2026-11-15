import type { ReactNode } from "react";

interface Props {
  readonly title: string;
  readonly description: ReactNode;
  readonly children: ReactNode;
}

/** One card on the settings page; every section shares its frame so the page reads as a set. */
export function SettingsSection({ title, description, children }: Props) {
  return (
    <section
      aria-label={title}
      className="card flex min-w-0 flex-col gap-4 p-4 sm:p-6"
    >
      <div>
        <h2 className="font-semibold tracking-tight [overflow-wrap:anywhere]">{title}</h2>
        <p className="mt-1 text-sm text-pretty text-zinc-500 dark:text-zinc-400">{description}</p>
      </div>
      {children}
    </section>
  );
}

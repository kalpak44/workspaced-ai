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
      className="flex flex-col gap-4 rounded-xl border border-zinc-200 p-6 dark:border-zinc-800"
    >
      <div>
        <h2 className="font-medium">{title}</h2>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{description}</p>
      </div>
      {children}
    </section>
  );
}

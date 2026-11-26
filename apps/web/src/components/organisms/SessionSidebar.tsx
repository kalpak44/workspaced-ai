import { ArrowLeft, Plus, Settings } from "lucide-react";
import type { Session } from "../../hooks/useSessions";
import { Button, IconButton } from "../atoms";
import { SessionDropdown } from "./SessionDropdown";
import { SessionNavigation } from "./SessionNavigation";

interface Props {
  readonly workspaceName: string;
  readonly sessions: readonly Session[];
  readonly selectedId: string;
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onBack: () => void;
  readonly onSettings: () => void;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

export function SessionSidebar({ workspaceName, sessions, selectedId, loading, deleting, onBack, onSettings, onOpen, onDelete }: Props) {
  return (
    <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] sm:flex sm:min-h-0 sm:flex-1 sm:flex-col">
      <div className="col-span-2 flex h-12 shrink-0 sm:h-14 items-center justify-between gap-2 border-b border-zinc-200 px-4 dark:border-zinc-800">
        <button type="button" aria-label="Back to workspace" onClick={onBack} className="flex min-w-0 items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white">
          <ArrowLeft aria-hidden className="size-4 shrink-0" /><span className="truncate">{workspaceName}</span>
        </button>
        <IconButton data-tour="session-settings" label="Workspace settings" title="Workspace settings" icon={Settings} onClick={onSettings} className="shrink-0" />
      </div>
      <nav aria-label="Session navigation" className="min-h-0 min-w-0 flex-1 p-3 sm:flex sm:flex-col sm:overflow-hidden">
        <div className="flex sm:hidden"><SessionDropdown sessions={sessions} selectedId={selectedId} loading={loading} deleting={deleting} onOpen={onOpen} onDelete={onDelete} /></div>
        <div className="hidden min-h-0 flex-1 sm:flex sm:flex-col">
          <SessionNavigation sessions={sessions} selectedId={selectedId} loading={loading} deleting={deleting} onOpen={onOpen} onDelete={onDelete} />
        </div>
      </nav>
      <div className="shrink-0 p-3 pl-0 sm:border-t sm:border-zinc-200 sm:pl-3 dark:border-zinc-800 [&>button]:w-full [&>button]:justify-center">
        <Button label="New session" variant="secondary" icon={Plus} onClick={onBack} />
      </div>
    </div>
  );
}

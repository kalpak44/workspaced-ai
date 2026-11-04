import { ArrowLeft, MessageSquare, Plus, Trash2 } from "lucide-react";
import type { Session } from "../../hooks/useSessions";
import { Button, IconButton, Spinner } from "../atoms";

interface Props {
  readonly workspaceName: string;
  readonly sessions: readonly Session[];
  readonly selectedId: string;
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onBack: () => void;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

export function SessionSidebar({ workspaceName, sessions, selectedId, loading, deleting, onBack, onOpen, onDelete }: Props) {
  return (
    <>
      <div className="flex h-14 shrink-0 items-center border-b border-zinc-200 px-4 dark:border-zinc-800">
        <button type="button" aria-label="Back to workspace" onClick={onBack} className="flex min-w-0 items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white">
          <ArrowLeft aria-hidden className="size-4 shrink-0" /><span className="truncate">{workspaceName}</span>
        </button>
      </div>
      <nav aria-label="Session navigation" className="min-h-0 flex-1 overflow-y-auto p-3">
        <div className="mb-3 pl-2">
          <h2 className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Sessions</h2>
        </div>
        {loading ? <p className="px-2 py-2 text-xs text-zinc-500">Loading sessions…</p> :
          <ul className="flex gap-1 overflow-x-auto sm:flex-col">
            {[...sessions].reverse().map((session) => (
              <li key={session.id} className={`group relative flex min-w-0 shrink-0 items-center rounded-md sm:shrink ${session.id === selectedId ? "bg-zinc-100 dark:bg-zinc-800" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"}`}>
                <button type="button" aria-current={session.id === selectedId ? "page" : undefined} disabled={session.available === false || deleting.has(session.id)} onClick={() => onOpen(session.id)}
                  className="flex min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left text-sm text-zinc-600 aria-[current=page]:font-medium aria-[current=page]:text-zinc-900 disabled:opacity-50 dark:text-zinc-400 dark:aria-[current=page]:text-zinc-100">
                  <MessageSquare aria-hidden className="size-3.5 shrink-0" /><span className="truncate">{session.title ?? "New session"}</span>
                </button>
                {deleting.has(session.id) ? <Spinner label={`Deleting ${session.title ?? "New session"}`} className="mr-2 size-3.5 shrink-0 text-zinc-400" /> : <IconButton label={`Delete session: ${session.title ?? "New session"}`} title="Delete session" icon={Trash2} onClick={() => onDelete(session.id)} className="mr-1 shrink-0 p-1 opacity-100 hover:text-rose-600 focus:opacity-100 sm:opacity-0 sm:group-hover:opacity-100" />}
              </li>
            ))}
          </ul>}
      </nav>
      <div className="shrink-0 border-t border-zinc-200 p-3 dark:border-zinc-800 [&>button]:w-full [&>button]:justify-center">
        <Button label="New session" variant="secondary" icon={Plus} onClick={onBack} />
      </div>
    </>
  );
}

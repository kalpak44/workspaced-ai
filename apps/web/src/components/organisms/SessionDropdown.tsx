import { ChevronDown, MessageSquare, Search, Trash2 } from "lucide-react";
import { useCallback, useId, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import type { Session } from "../../hooks/useSessions";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly sessions: readonly Session[];
  readonly selectedId?: string;
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

/** The same compact, searchable session picker on both mobile conversation screens. */
export function SessionDropdown({ sessions, selectedId, loading, deleting, onOpen, onDelete }: Props) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const navigation = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const listId = useId();
  const close = useCallback(() => { setOpen(false); setQuery(""); }, []);
  useDismiss(navigation, open, close);
  const selected = sessions.find((session) => session.id === selectedId);
  const filtered = [...sessions].reverse().filter((session) =>
    (session.title ?? "New session").toLowerCase().includes(query.trim().toLowerCase()));

  return (
    <div ref={navigation} className="relative min-w-0 flex-1">
        <button ref={trigger} type="button" aria-label="Choose session" aria-expanded={open} aria-controls={listId}
          onClick={() => { setQuery(""); setOpen(!open); }}
          className="flex min-h-11 w-full min-w-0 items-center gap-2 rounded-md border border-zinc-300 px-3 text-sm dark:border-zinc-700">
          <MessageSquare aria-hidden className="size-4 shrink-0" />
          <span className="min-w-0 flex-1 truncate text-left">{loading ? "Loading sessions…" : selectedId ? selected?.title ?? "New session" : "Recent sessions"}</span>
          <ChevronDown aria-hidden className="size-4 shrink-0" />
        </button>
        {open && <div id={listId} onKeyDown={(event) => {
          if (event.key === "Escape") { close(); trigger.current?.focus(); }
        }} role="region" aria-label="Session picker" className={`absolute left-0 top-full z-20 mt-2 w-[calc(100vw-1.5rem)] max-w-sm rounded-lg border border-zinc-200 bg-white p-2 shadow-xl dark:border-zinc-700 dark:bg-zinc-900`}>
        {open && <label className="mb-2 flex items-center gap-2 rounded-md border border-zinc-300 px-3 dark:border-zinc-700">
          <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
          <input type="search" aria-label="Search sessions" placeholder="Search sessions…" autoFocus value={query}
            onChange={(event) => setQuery(event.target.value)} className="min-h-11 min-w-0 w-full bg-transparent text-sm outline-none" />
        </label>}
        <div className="sr-only">
          <h2 className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Sessions</h2>
        </div>
        {loading ? <p className="px-2 py-2 text-xs text-zinc-500">Loading sessions…</p> :
          <ul className="max-h-[40dvh] space-y-1 overflow-y-auto">
            {filtered.map((session) => (
              <li key={session.id} className={`group relative flex min-w-0 items-center rounded-md ${session.id === selectedId ? "bg-zinc-100 dark:bg-zinc-800" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"}`}>
                <button type="button" aria-current={session.id === selectedId ? "page" : undefined} disabled={session.available === false || deleting.has(session.id)} onClick={() => { close(); trigger.current?.focus(); onOpen(session.id); }}
                  className="flex min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left text-sm text-zinc-600 aria-[current=page]:font-medium aria-[current=page]:text-zinc-900 disabled:opacity-50 dark:text-zinc-400 dark:aria-[current=page]:text-zinc-100">
                  <MessageSquare aria-hidden className="size-3.5 shrink-0" /><span className="truncate">{session.title ?? "New session"}</span>
                </button>
                {deleting.has(session.id) ? <Spinner label={`Deleting ${session.title ?? "New session"}`} className="mr-2 size-3.5 shrink-0 text-zinc-400" /> : <IconButton label={`Delete session: ${session.title ?? "New session"}`} title="Delete session" icon={Trash2} onClick={() => { close(); onDelete(session.id); }} className="mr-1 shrink-0 p-1 opacity-100 hover:text-rose-600 focus:opacity-100" />}
              </li>
            ))}
          </ul>}
        {!loading && filtered.length === 0 && <p className="px-2 py-3 text-sm text-zinc-500">{query.trim() ? "No matching sessions." : "No sessions yet."}</p>}
        </div>}
    </div>
  );
}

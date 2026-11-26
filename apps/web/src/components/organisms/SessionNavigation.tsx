import { CalendarClock, MessageSquare, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import type { Session } from "../../hooks/useSessions";
import { IconButton, Select, Spinner } from "../atoms";
import { SearchField } from "../molecules/SearchField";

interface Props {
  readonly sessions: readonly Session[];
  readonly selectedId?: string;
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
  readonly autoFocus?: boolean;
}

/** Shared by the desktop sidebar and the mobile picker, so scheduled runs stay out of the way. */
export function SessionNavigation({ sessions, selectedId, loading, deleting, onOpen, onDelete, autoFocus }: Props) {
  const selected = sessions.find((session) => session.id === selectedId);
  const selectedGroup = selected ? (selected.task ? "scheduled" : "user") : undefined;
  const [group, setGroup] = useState(selectedGroup ?? "user");
  const [query, setQuery] = useState("");
  // Follow a deep link or browser navigation, including when the session list arrives later.
  useEffect(() => {
    if (selectedGroup) setGroup(selectedGroup);
    setQuery("");
  }, [selectedId, selectedGroup]);
  const scheduledCount = sessions.filter((session) => session.task).length;
  const needle = query.trim().toLowerCase();
  const filtered = [...sessions].reverse().filter((session) =>
    (group === "scheduled" ? !!session.task : !session.task)
    && `${session.title ?? "New session"} ${session.task ?? ""}`.toLowerCase().includes(needle));

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="shrink-0 space-y-2">
        {(scheduledCount > 0 || group === "scheduled") && (
          <Select aria-label="Sessions shown" value={group} onChange={(event) => { setGroup(event.target.value); setQuery(""); }} className="w-full [&>select]:min-h-11 sm:[&>select]:min-h-9">
            <option value="user">Your sessions ({sessions.length - scheduledCount})</option>
            <option value="scheduled">Scheduled runs ({scheduledCount})</option>
          </Select>
        )}
        <SearchField label="Search sessions" placeholder={group === "scheduled" ? "Search scheduled runs…" : "Search sessions…"} value={query} onChange={setQuery} autoFocus={autoFocus} className="[&>input]:min-h-11 sm:[&>input]:min-h-9" />
      </div>
      <div className="flex shrink-0 items-center justify-between gap-2 px-2 text-xs text-zinc-500 dark:text-zinc-400">
        <h2 className="font-medium">{group === "scheduled" ? "Scheduled runs" : "Your sessions"}</h2>
        {!loading && <span className="tabular-nums">{filtered.length}</span>}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {loading ? <p className="px-2 py-2 text-xs text-zinc-500">Loading sessions…</p> : filtered.length === 0 ? (
          <p className="px-2 py-4 text-sm text-zinc-500 dark:text-zinc-400">
            {needle ? "No matching sessions." : group === "scheduled" ? "No scheduled runs yet." : "No sessions yet."}
          </p>
        ) : (
          <ul className="space-y-1">
            {filtered.map((session) => {
              const title = session.title ?? "New session";
              const Icon = session.task ? CalendarClock : MessageSquare;
              return (
                <li key={session.id} className={`group relative flex min-w-0 items-center rounded-md ${session.id === selectedId ? "bg-zinc-100 dark:bg-zinc-800" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"}`}>
                  <button type="button" aria-label={title} title={title} aria-current={session.id === selectedId ? "page" : undefined}
                    disabled={session.available === false || deleting.has(session.id)} onClick={() => onOpen(session.id)}
                    className="flex min-h-11 min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left text-sm text-zinc-600 aria-[current=page]:font-medium aria-[current=page]:text-zinc-900 disabled:opacity-50 dark:text-zinc-400 dark:aria-[current=page]:text-zinc-100">
                    <Icon aria-hidden className="size-3.5 shrink-0" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{title}</span>
                      {session.available === false ? <span className="mt-0.5 block text-xs font-normal text-zinc-500">Conversation unavailable</span> : session.task && (
                        <time dateTime={session.createdAt} title={new Date(session.createdAt).toLocaleString()} className="mt-0.5 block truncate text-xs font-normal text-zinc-500 dark:text-zinc-400">
                          {new Date(session.createdAt).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}
                        </time>
                      )}
                    </span>
                  </button>
                  {deleting.has(session.id) ? <Spinner label={`Deleting ${title}`} className="mr-2 size-3.5 shrink-0 text-zinc-400" /> : (
                    <IconButton label={`Delete session: ${title}`} title="Delete session" icon={Trash2} onClick={() => onDelete(session.id)} className="mr-1 min-h-11 min-w-11 shrink-0 opacity-100 sm:min-h-0 sm:min-w-0 hover:text-rose-600 focus:opacity-100 sm:opacity-0 sm:group-hover:opacity-100" />
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

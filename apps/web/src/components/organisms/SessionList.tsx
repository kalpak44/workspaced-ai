import { Trash2 } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { usePagination } from "../../hooks/usePagination";
import type { Attachment } from "../../hooks/useSessionFiles";
import type { Session } from "../../hooks/useSessions";
import { Button, Select, Skeleton, Spinner } from "../atoms";
import { ChatComposer, ErrorBanner, Pagination, SearchField, SessionCard } from "../molecules";

const PAGE_SIZE = 10;

type Tab = "user" | "scheduled";


interface Props {
  readonly modelControl?: ReactNode;
  readonly sessions: readonly Session[];
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  /** Starts a session with this first message and opens it. */
  readonly onStart: (text: string) => Promise<boolean>;
  /** Files waiting for the session the first message starts. */
  readonly attachments?: readonly Attachment[];
  readonly onAttach?: (files: File[]) => void;
  readonly onDetach?: (id: string) => void;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
  /** Asks to delete these; they stay selected until they are gone, so a cancel keeps the choice. */
  readonly onDeleteMany: (ids: readonly string[]) => void;
}

/** A session row's outline, shaped like SessionCard, while the list loads. */
function Placeholder({ width }: { readonly width: string }) {
  return (
    <li aria-hidden className="flex items-center gap-3 px-4 py-3.5">
      <Skeleton className="size-8 shrink-0 rounded-lg" />
      <span className="flex min-w-0 flex-1 flex-col gap-2 py-0.5">
        <Skeleton className={`h-3 ${width}`} />
        <Skeleton className="h-2.5 w-16" />
      </span>
    </li>
  );
}

/** Where a workspace's work starts: type a task, or pick up an earlier session. */
export function SessionList(props: Props) {
  const { loading } = props;
  // Task runs are kept apart, so the ones a schedule keeps starting never bury the user's own.
  const [tab, setTab] = useState<Tab>("user");
  const scheduled = props.sessions.filter((session) => session.task);
  const [query, setQuery] = useState("");
  const needle = query.trim().toLowerCase();
  const sessions = (tab === "scheduled" ? scheduled : props.sessions.filter((session) => !session.task))
    .filter((session) => needle === "" || (session.title ?? "New session").toLowerCase().includes(needle));
  // Null outside selection mode; the ids chosen for deletion inside it.
  const [selected, setSelected] = useState<ReadonlySet<string> | null>(null);
  // Newest first: the session most likely to be wanted is the one last worked on.
  const newestFirst = [...sessions].reverse();
  const pages = usePagination(newestFirst, PAGE_SIZE, `${tab}:${needle}`);

  // A session deleted meanwhile, here or elsewhere, is no longer chosen; once every chosen one is
  // gone, the deletion is done, and so is selecting.
  useEffect(() => {
    setSelected((current) => {
      if (current === null) return current;
      const present = new Set([...current].filter((id) => sessions.some((session) => session.id === id)));
      if (present.size === current.size) return current;
      return present.size === 0 ? null : present;
    });
  }, [sessions]);

  const toggle = (id: string) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const allSelected = selected !== null && sessions.length > 0 && selected.size === sessions.length;
  const switchTab = (next: Tab) => {
    setTab(next);
    setSelected(null);
  };

  return (
    <section aria-label="Sessions" className="mx-auto flex w-full max-w-5xl flex-col gap-8 px-4 pt-6 pb-10 sm:px-6 sm:pt-8">
      {props.error && <ErrorBanner message={props.error} onDismiss={props.onDismissError} />}
      <div>
        <h2 className="mb-1 text-xl font-semibold tracking-tight sm:text-2xl">Start a session</h2>
        <p className="mb-5 text-sm text-zinc-500 dark:text-zinc-400">Describe a task and the agent starts working on it, or pick up a recent conversation below.</p>
        <ChatComposer
          busy={false}
          modelControl={props.modelControl}
          autoFocus
          placeholder="Describe a task, and a new session starts on it…"
          onSend={props.onStart}
          onStop={() => {}}
          attachments={props.attachments}
          onAttach={props.onAttach}
          onDetach={props.onDetach}
        />
      </div>
      <div>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            <h2 className="text-sm font-semibold">{tab === "scheduled" ? "Scheduled runs" : "Recent sessions"}</h2>
            {loading ? (
              <Spinner label="Loading sessions" className="size-3.5 text-zinc-400" />
            ) : (
              <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium tabular-nums text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400">{sessions.length}</span>
            )}
          </div>
          <div className="flex min-w-0 flex-wrap items-center gap-2 max-sm:w-full sm:flex-nowrap">
            {/* A filter, not navigation: the runs a schedule keeps starting are kept apart from the user's own. */}
            {(scheduled.length > 0 || tab === "scheduled") && (
              <Select aria-label="Sessions shown" value={tab} onChange={(event) => switchTab(event.target.value as Tab)} className="max-sm:flex-1 sm:shrink-0">
                <option value="user">Your sessions</option>
                <option value="scheduled">Scheduled runs</option>
              </Select>
            )}
            {!loading && (sessions.length > 0 || needle !== "") && (
              <SearchField label="Search sessions" placeholder="Search sessions…" value={query} onChange={setQuery} className="order-last basis-full sm:order-none sm:basis-auto sm:w-52" />
            )}
            {!loading && sessions.length > 0 && selected === null && (
              <button type="button" onClick={() => setSelected(new Set())}
                className="ml-auto shrink-0 rounded-md px-2 py-1 sm:ml-0 text-xs font-medium text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100">
                Select
              </button>
            )}
          </div>
        </div>
        {selected !== null && (
          <div role="toolbar" aria-label="Selected sessions" className="sticky top-16 z-[5] mb-3 flex animate-fade-in flex-wrap items-center gap-3 rounded-xl border border-zinc-200 bg-white/95 px-4 py-2 text-sm shadow-sm backdrop-blur dark:border-zinc-800 dark:bg-zinc-900/95">
            <label className="flex items-center gap-2 text-zinc-600 dark:text-zinc-300">
              <input type="checkbox" checked={allSelected} className="size-4 accent-zinc-900 dark:accent-zinc-100"
                onChange={() => setSelected(allSelected ? new Set() : new Set(sessions.map((session) => session.id)))} />
              Select all {sessions.length}
            </label>
            <span className="text-xs text-zinc-500 dark:text-zinc-400">{selected.size} selected</span>
            <span className="ml-auto flex gap-2">
              <Button label="Cancel" variant="secondary" onClick={() => setSelected(null)} />
              <Button label={selected.size > 0 ? `Delete ${selected.size}` : "Delete"} variant="danger" icon={Trash2}
                disabled={selected.size === 0} onClick={() => props.onDeleteMany([...selected])} />
            </span>
          </div>
        )}
        {!loading && sessions.length === 0 ? (
          <p className="rounded-xl border border-dashed border-zinc-300 px-4 py-10 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
            {needle !== ""
              ? `No sessions match “${query.trim()}”.`
              : tab === "scheduled"
              ? "No scheduled runs yet. Each run of a scheduled task opens a session here."
              : "No sessions yet. Start a task to work with your workspace’s files."}
          </p>
        ) : (
          <ul className="card flex flex-col divide-y divide-zinc-200 overflow-hidden dark:divide-zinc-800" aria-busy={loading}>
            {loading ? (
              <>
                <Placeholder width="w-2/5" />
                <Placeholder width="w-3/5" />
                <Placeholder width="w-1/3" />
              </>
            ) : (
              pages.items.map((session) => (
                <SessionCard
                  key={session.id}
                  session={session}
                  deleting={props.deleting.has(session.id)}
                  onOpen={() => props.onOpen(session.id)}
                  onDelete={() => props.onDelete(session.id)}
                  selected={selected === null ? undefined : selected.has(session.id)}
                  onToggle={() => toggle(session.id)}
                />
              ))
            )}
          </ul>
        )}
        {!loading && (
          <div className="mt-4">
            <Pagination label="Session pages" noun="session" page={pages.page} pageCount={pages.pageCount} onChange={pages.setPage}
              range={{ first: pages.first, last: pages.last, total: pages.total }} />
          </div>
        )}
      </div>
    </section>
  );
}

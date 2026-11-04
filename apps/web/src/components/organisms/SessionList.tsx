import { ChevronLeft, ChevronRight } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import type { Session } from "../../hooks/useSessions";
import { IconButton } from "../atoms";
import { ChatComposer, ErrorBanner, SessionCard } from "../molecules";

const PAGE_SIZE = 10;

interface Props {
  readonly modelControl?: ReactNode;
  readonly sessions: readonly Session[];
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  /** Starts a session with this first message and opens it. */
  readonly onStart: (text: string) => Promise<boolean>;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

function Placeholder() {
  return (
    <li aria-hidden className="h-[62px] animate-pulse rounded-lg bg-zinc-100 dark:bg-zinc-800/60" />
  );
}

/** Where a workspace's work starts: type a task, or pick up an earlier session. */
export function SessionList(props: Props) {
  const { sessions, loading } = props;
  const [page, setPage] = useState(1);
  // Newest first: the session most likely to be wanted is the one last worked on.
  const newestFirst = [...sessions].reverse();
  const pageCount = Math.max(1, Math.ceil(newestFirst.length / PAGE_SIZE));
  const visibleSessions = newestFirst.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  useEffect(() => {
    setPage((current) => Math.min(current, pageCount));
  }, [pageCount]);

  return (
    <section aria-label="Sessions" className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-4 py-8 sm:px-6 sm:py-10">
      {props.error && <ErrorBanner message={props.error} onDismiss={props.onDismissError} />}
      <div>
        <h2 className="mb-2 text-xl font-semibold tracking-tight">Start a session</h2>
        <p className="mb-5 text-sm text-zinc-500 dark:text-zinc-400">Start a new task or continue a recent conversation.</p>
        <ChatComposer
          busy={false}
          modelControl={props.modelControl}
          autoFocus
          placeholder="Describe a task, and a new session starts on it…"
          onSend={props.onStart}
          onStop={() => {}}
        />
      </div>
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="flex items-baseline gap-2 text-sm font-medium text-zinc-600 dark:text-zinc-300">
            Recent sessions
            {!loading && <span className="text-xs font-normal text-zinc-400">{sessions.length}</span>}
          </h2>
          {!loading && pageCount > 1 && (
            <span className="text-xs text-zinc-400">Page {page} of {pageCount}</span>
          )}
        </div>
        {!loading && sessions.length === 0 ? (
          <p className="rounded-lg border border-dashed border-zinc-300 px-4 py-8 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
            No sessions yet. Start a task to work with your workspace’s files.
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800" aria-busy={loading}>
            {loading ? (
              <>
                <Placeholder />
                <Placeholder />
              </>
            ) : (
              visibleSessions.map((session) => (
                <SessionCard
                  key={session.id}
                  session={session}
                  deleting={props.deleting.has(session.id)}
                  onOpen={() => props.onOpen(session.id)}
                  onDelete={() => props.onDelete(session.id)}
                />
              ))
            )}
          </ul>
        )}
        {!loading && pageCount > 1 && (
          <nav aria-label="Session pages" className="mt-5 flex flex-wrap items-center justify-end gap-1">
            <IconButton
              label="Previous session page"
              icon={ChevronLeft}
              disabled={page === 1}
              onClick={() => setPage((current) => Math.max(1, current - 1))}
            />
            {Array.from({ length: pageCount }, (_, index) => index + 1).map((number) => (
              <button
                key={number}
                type="button"
                aria-label={`Session page ${number}`}
                aria-current={page === number ? "page" : undefined}
                onClick={() => setPage(number)}
                className="size-8 rounded-md text-xs font-medium text-zinc-500 hover:bg-zinc-100 hover:text-zinc-950 aria-current:bg-zinc-900 aria-current:text-white dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white dark:aria-current:bg-zinc-100 dark:aria-current:text-zinc-950"
              >
                {number}
              </button>
            ))}
            <IconButton
              label="Next session page"
              icon={ChevronRight}
              disabled={page === pageCount}
              onClick={() => setPage((current) => Math.min(pageCount, current + 1))}
            />
          </nav>
        )}
      </div>
    </section>
  );
}

import type { Session } from "../../hooks/useSessions";
import { ChatComposer, ErrorBanner, SessionCard } from "../molecules";

interface Props {
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
  // Newest first: the session most likely to be wanted is the one last worked on.
  const newestFirst = [...sessions].reverse();

  return (
    <section aria-label="Sessions" className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-6 py-8">
      {props.error && <ErrorBanner message={props.error} onDismiss={props.onDismissError} />}
      <div>
        <h2 className="mb-3 text-xl font-semibold">Start a session</h2>
        <ChatComposer
          busy={false}
          autoFocus
          placeholder="Describe a task, and a new session starts on it…"
          onSend={props.onStart}
          onStop={() => {}}
        />
      </div>
      <div>
        <h2 className="mb-3 flex items-baseline gap-2 text-sm font-medium text-zinc-500 dark:text-zinc-400">
          Sessions
          {!loading && <span className="text-xs">{sessions.length}</span>}
        </h2>
        {!loading && sessions.length === 0 ? (
          <p className="rounded-lg border border-dashed border-zinc-300 px-4 py-8 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
            No sessions yet. Each one gets its own folder in the workspace, so tasks never
            trip over each other.
          </p>
        ) : (
          <ul className="flex flex-col gap-2" aria-busy={loading}>
            {loading ? (
              <>
                <Placeholder />
                <Placeholder />
              </>
            ) : (
              newestFirst.map((session) => (
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
      </div>
    </section>
  );
}

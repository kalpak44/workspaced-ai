import { MessageSquare, Plus } from "lucide-react";
import { useEffect, useRef } from "react";
import type { ChatMessage } from "../../hooks/useChat";
import type { Session } from "../../hooks/useSessions";
import { Spinner } from "../atoms";
import { ChatComposer, ChatMessageView, ErrorBanner, SessionItem } from "../molecules";

interface Props {
  readonly sessions: readonly Session[];
  readonly selectedSessionId: string | null;
  readonly deletingSessions: ReadonlySet<string>;
  readonly onSelectSession: (id: string) => void;
  readonly onNewSession: () => void;
  readonly onDeleteSession: (id: string) => void;
  readonly messages: readonly ChatMessage[];
  readonly loading: boolean;
  readonly busy: boolean;
  readonly error: string | null;
  readonly onDismissError: () => void;
  /** With no session open, the page starts one first, so typing is always enough. */
  readonly onSend: (text: string) => Promise<boolean>;
  readonly onStop: () => void;
}

/** A workspace's sessions down the side, and the open one's conversation beside them. */
export function ChatPanel(props: Props) {
  const { sessions, selectedSessionId, messages, loading, busy, error } = props;
  const transcript = useRef<HTMLDivElement>(null);

  // Follows the reply as it streams in.
  useEffect(() => {
    const element = transcript.current;
    if (element) {
      element.scrollTop = element.scrollHeight;
    }
  }, [messages]);

  let body;
  if (loading) {
    body = (
      <div className="flex flex-1 items-center justify-center">
        <Spinner label="Loading the conversation" className="size-6 text-zinc-400" />
      </div>
    );
  } else if (messages.length === 0) {
    body = (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
        <MessageSquare aria-hidden className="mb-2 size-8 text-zinc-400" />
        <h3 className="text-2xl font-semibold">Start a conversation</h3>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          Each session works in its own folder inside the workspace
        </p>
      </div>
    );
  } else {
    body = (
      <ol className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-6">
        {messages.map((message) => (
          <ChatMessageView key={message.id} message={message} />
        ))}
        {busy && (
          <li className="flex items-center gap-2 text-xs text-zinc-500">
            <Spinner label="Agent is working" className="size-3.5" />
            Working…
          </li>
        )}
      </ol>
    );
  }

  return (
    <section aria-label="Chat" className="flex min-h-0 flex-1 border-t border-zinc-200 dark:border-zinc-800">
      <nav
        aria-label="Sessions"
        className="flex w-56 shrink-0 flex-col border-r border-zinc-200 dark:border-zinc-800"
      >
        <button
          type="button"
          onClick={props.onNewSession}
          className="m-2 flex items-center gap-2 rounded-md border border-zinc-300 px-2 py-1.5 text-sm hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          <Plus aria-hidden className="size-4" />
          New session
        </button>
        <ul className="flex-1 space-y-0.5 overflow-y-auto px-2 text-sm">
          {sessions.map((session) => (
            <SessionItem
              key={session.id}
              session={session}
              selected={session.id === selectedSessionId}
              deleting={props.deletingSessions.has(session.id)}
              onSelect={() => props.onSelectSession(session.id)}
              onDelete={() => props.onDeleteSession(session.id)}
            />
          ))}
        </ul>
      </nav>
      <div className="flex min-w-0 flex-1 flex-col">
        {error && <ErrorBanner message={error} onDismiss={props.onDismissError} />}
        <div ref={transcript} className="flex min-h-0 flex-1 flex-col overflow-y-auto">
          {body}
        </div>
        <div className="mx-auto w-full max-w-3xl px-4 pb-4">
          <ChatComposer busy={busy} onSend={props.onSend} onStop={props.onStop} />
        </div>
      </div>
    </section>
  );
}

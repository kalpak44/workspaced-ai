import { Sparkles } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import type { ChatMessage } from "../../hooks/useChat";
import { Spinner } from "../atoms";
import { ChatComposer, ChatMessageEditor, ChatMessageView, ErrorBanner } from "../molecules";

interface Props {
  readonly messages: readonly ChatMessage[];
  readonly loading: boolean;
  readonly busy: boolean;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onSend: (text: string) => Promise<boolean>;
  readonly onStop: () => void;
  readonly onEdit: (messageId: string, text: string) => Promise<boolean>;
  readonly modelControl?: ReactNode;
}

/** One session's conversation, with the composer pinned below it. */
export function ChatPanel({ messages, loading, busy, error, onDismissError, onSend, onStop, onEdit, modelControl }: Props) {
  const [editing, setEditing] = useState<ChatMessage | null>(null);
  // Keep the editor mounted even if a revert event removes the original message before
  // the request settles. A failed replacement must leave the draft available to copy.
  const displayed = editing && !messages.some((m) => m.id === editing.id)
    ? [...messages, editing] : messages;
  const transcript = useRef<HTMLDivElement>(null);

  // Follows the reply as it streams in.
  useEffect(() => {
    const element = transcript.current;
    if (element) {
      element.scrollTop = element.scrollHeight;
    }
  }, [messages, busy]);

  let body;
  if (loading) {
    body = (
      <div className="flex flex-1 items-center justify-center">
        <Spinner label="Loading the conversation" className="size-6 text-zinc-400" />
      </div>
    );
  } else if (displayed.length === 0 && !busy) {
    body = (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 px-6 text-center">
        <Sparkles aria-hidden className="mb-2 size-8 text-zinc-400" />
        <h2 className="text-xl font-semibold">What should the agent do?</h2>
        <p className="max-w-sm text-sm text-zinc-500 dark:text-zinc-400">
          It works in this session's own folder inside the workspace, and you can watch it as
          it goes.
        </p>
      </div>
    );
  } else {
    body = (
      <ol className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-6 py-6">
        {displayed.map((message) => message.id === editing?.id ? (
          <ChatMessageEditor
            key={message.id}
            message={editing}
            busy={busy}
            onCancel={() => setEditing(null)}
            onSave={async (text) => {
              const saved = await onEdit(message.id, text);
              if (saved) setEditing(null);
              return saved;
            }}
          />
        ) : (
          <ChatMessageView key={message.id} message={message} busy={busy || editing !== null}
            onEdit={() => setEditing(message)} />
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
    <section aria-label="Chat" className="flex min-h-0 flex-1 flex-col">
      {error && <ErrorBanner message={error} onDismiss={onDismissError} />}
      <div ref={transcript} className="flex min-h-0 flex-1 flex-col overflow-y-auto">
        {body}
      </div>
      <div className="mx-auto w-full max-w-3xl px-6 pb-6">
        <div className="mb-2 flex items-center gap-2">{modelControl}</div>
        <ChatComposer busy={busy} disabled={editing !== null} autoFocus onSend={onSend} onStop={onStop} />
        <p className="mt-2 text-center text-xs text-zinc-400">
          Enter to send · Shift+Enter for a new line
        </p>
      </div>
    </section>
  );
}

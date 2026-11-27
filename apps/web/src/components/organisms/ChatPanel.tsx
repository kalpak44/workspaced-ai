import { ArrowDown, Sparkles } from "lucide-react";
import { type ReactNode, useEffect, useRef, useState } from "react";
import type { ChatMessage } from "../../hooks/useChat";
import type { AgentForm, FormAnswer } from "../../hooks/useChat";
import type { Attachment } from "../../hooks/useSessionFiles";
import { AgentQuestion } from "../molecules/AgentQuestion";
import { Spinner } from "../atoms";
import { ChatComposer, ChatMessageEditor, ChatMessageView, ErrorBanner } from "../molecules";

interface Props {
  readonly messages: readonly ChatMessage[];
  readonly loading: boolean;
  readonly busy: boolean;
  readonly disabled?: boolean;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onSend: (text: string) => Promise<boolean>;
  readonly onStop: () => void;
  readonly onEdit: (messageId: string, text: string) => Promise<boolean>;
  readonly modelControl?: ReactNode;
  readonly creditNotice?: ReactNode;
  readonly forms: readonly AgentForm[];
  readonly onAnswer: (id: string, answer?: FormAnswer) => Promise<boolean>;
  readonly attachments?: readonly Attachment[];
  readonly onAttach?: (files: File[]) => void;
  readonly onDetach?: (id: string) => void;
}

/** One session's conversation, with the composer pinned below it. */
export function ChatPanel({ messages, loading, busy, disabled = false, error, onDismissError, onSend, onStop, onEdit, modelControl, creditNotice, forms, onAnswer, attachments, onAttach, onDetach }: Props) {
  const [editing, setEditing] = useState<ChatMessage | null>(null);
  // Keep the editor mounted even if a revert event removes the original message before
  // the request settles. A failed replacement must leave the draft available to copy.
  const displayed = editing && !messages.some((m) => m.id === editing.id)
    ? [...messages, editing] : messages;
  const transcript = useRef<HTMLDivElement>(null);
  // Whether the reader is at the end; scrolling up to reread pauses following the reply.
  const following = useRef(true);
  const [behind, setBehind] = useState(false);

  // Follows the reply as it streams in, unless the reader scrolled away from the end.
  useEffect(() => {
    const element = transcript.current;
    if (element && following.current) {
      element.scrollTop = element.scrollHeight;
    } else if (element) {
      setBehind(true);
    }
  }, [messages, busy, forms]);

  const onScroll = () => {
    const element = transcript.current;
    if (!element) return;
    following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
    if (following.current) setBehind(false);
  };

  const jumpToLatest = () => {
    const element = transcript.current;
    following.current = true;
    setBehind(false);
    element?.scrollTo?.({ top: element.scrollHeight, behavior: "smooth" });
  };

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
        <span className="mb-2 flex size-12 items-center justify-center rounded-full bg-zinc-100 text-zinc-500 ring-8 ring-zinc-100/40 dark:bg-zinc-800 dark:text-zinc-400 dark:ring-zinc-800/30">
          <Sparkles aria-hidden className="size-5" />
        </span>
        <h2 className="text-xl font-semibold tracking-tight">What should the agent do?</h2>
        <p className="max-w-sm text-sm text-zinc-500 dark:text-zinc-400">
          It works in this session's own folder inside the workspace, and you can watch it as
          it goes.
        </p>
      </div>
    );
  } else {
    body = (
      <ol className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-6 sm:px-6">
        {displayed.map((message) => message.id === editing?.id ? (
          <ChatMessageEditor
            key={message.id}
            message={editing}
            busy={busy || disabled}
            onCancel={() => setEditing(null)}
            onSave={async (text) => {
              const saved = await onEdit(message.id, text);
              if (saved) setEditing(null);
              return saved;
            }}
          />
        ) : (
          <ChatMessageView key={message.id} message={message} busy={busy || disabled || editing !== null}
            onEdit={() => setEditing(message)} />
        ))}
        {forms.map((form) => <li key={form.id}><AgentQuestion form={form} onAnswer={onAnswer} /></li>)}
        {busy && forms.length === 0 && (
          <li className="flex w-fit items-center gap-2 rounded-full border border-zinc-200 bg-white px-3 py-1.5 text-xs text-zinc-500 shadow-xs dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-400">
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
      <div ref={transcript} onScroll={onScroll} className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain">
        {body}
      </div>
      <div className="relative mx-auto w-full max-w-3xl shrink-0 px-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:px-6 sm:pb-6">
        {behind && (
          <button type="button" onClick={jumpToLatest}
            className="absolute -top-12 left-1/2 flex -translate-x-1/2 animate-pop-in items-center gap-1.5 rounded-full border border-zinc-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 shadow-md hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800">
            <ArrowDown aria-hidden className="size-3.5" />Jump to latest
          </button>
        )}
        {creditNotice}
        <ChatComposer busy={busy} disabled={disabled || editing !== null || forms.length > 0} modelControl={modelControl} autoFocus onSend={onSend} onStop={onStop}
          attachments={attachments} onAttach={onAttach} onDetach={onDetach} />
        <p className="mt-2 text-center text-xs text-zinc-400 pointer-coarse:hidden">
          Enter to send · Shift+Enter for a new line
        </p>
      </div>
    </section>
  );
}

import { ChevronRight, CircleAlert, CircleCheck, Pencil, Wrench } from "lucide-react";
import { useState } from "react";
import type { ChatMessage, ChatPart } from "../../hooks/useChat";
import { Button, IconButton, Spinner } from "../atoms";
import { Markdown } from "./Markdown";

/** What a tool call is about in one line: its own description, else its command or first text. */
function toolSummary(input: ChatPart["input"]): string | null {
  if (!input) return null;
  for (const key of ["description", "command", "filePath", "path", "pattern", "url", "query"]) {
    if (typeof input[key] === "string" && input[key]) return input[key];
  }
  return Object.values(input).find((value): value is string => typeof value === "string" && value !== "") ?? null;
}

/**
 * A tool call as one line, which opens to what it was called with and what came back: a shell
 * command as the command itself, any other tool as its arguments.
 */
function ToolPart({ part }: { readonly part: ChatPart }) {
  let state;
  if (part.status === "completed") {
    state = <CircleCheck role="img" aria-label="Done" className="size-3.5 text-emerald-500" />;
  } else if (part.status === "error") {
    state = <CircleAlert role="img" aria-label="Failed" className="size-3.5 text-rose-500" />;
  } else {
    state = <Spinner label="Running" className="size-3.5 text-zinc-400" />;
  }
  const summary = toolSummary(part.input);
  const command = typeof part.input?.command === "string" ? part.input.command : null;
  const hasDetails = Boolean(part.input && Object.keys(part.input).length > 0) || Boolean(part.output);
  const line = (
    <>
      <Wrench aria-hidden className="size-3.5 shrink-0" />
      <span className="shrink-0 font-mono">{part.tool}</span>
      {summary && <span className="truncate text-zinc-500 dark:text-zinc-500">{summary}</span>}
      <span className="ml-auto shrink-0">{state}</span>
    </>
  );
  const frame = "rounded-md border border-zinc-200 text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400";
  if (!hasDetails) {
    return <div className={`flex items-center gap-2 px-2.5 py-1.5 ${frame}`}>{line}</div>;
  }
  const block = "max-h-80 overflow-auto whitespace-pre-wrap px-2.5 py-2 font-mono [overflow-wrap:anywhere]";
  return (
    <details className={`group ${frame}`}>
      <summary className="flex cursor-pointer list-none items-center gap-2 px-2.5 py-1.5 select-none hover:bg-zinc-50 dark:hover:bg-zinc-900 [&::-webkit-details-marker]:hidden">
        <ChevronRight aria-hidden className="size-3 shrink-0 transition-transform group-open:rotate-90" />
        {line}
      </summary>
      <div className="divide-y divide-zinc-200 border-t border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
        {command !== null ? (
          <pre aria-label="Command" className={`${block} text-zinc-800 dark:text-zinc-200`}>$ {command}</pre>
        ) : (
          part.input && Object.keys(part.input).length > 0 && (
            <pre aria-label="Input" className={`${block} text-zinc-800 dark:text-zinc-200`}>{JSON.stringify(part.input, null, 2)}</pre>
          )
        )}
        {part.output ? (
          <pre aria-label="Output" className={`${block} bg-zinc-50 dark:bg-zinc-900/60 ${part.status === "error" ? "text-rose-600 dark:text-rose-400" : ""}`}>{part.output}</pre>
        ) : (
          part.status === "completed" && <p className="px-2.5 py-2 text-zinc-400">No output.</p>
        )}
      </div>
    </details>
  );
}

/** Reasoning is folded away: it is useful when something goes wrong, noise otherwise. */
function ReasoningPart({ text }: { readonly text: string }) {
  return (
    <details className="group text-xs text-zinc-500 dark:text-zinc-400">
      <summary className="cursor-pointer select-none hover:text-zinc-700 dark:hover:text-zinc-200">
        Thinking
      </summary>
      <p className="mt-1 border-l-2 border-zinc-200 pl-3 whitespace-pre-wrap dark:border-zinc-700">
        {text}
      </p>
    </details>
  );
}

function Part({ part, markdown }: { readonly part: ChatPart; readonly markdown: boolean }) {
  if (part.type === "tool") {
    return <ToolPart part={part} />;
  }
  if (!part.text) {
    return null;
  }
  if (part.type === "reasoning") {
    return <ReasoningPart text={part.text} />;
  }
  return markdown ? <Markdown text={part.text} /> : <p className="whitespace-pre-wrap">{part.text}</p>;
}

/**
 * The user's messages as bubbles on the right, kept as typed; the agent's in full width,
 * rendered as the markdown it writes.
 */
export function ChatMessageEditor({ message, busy, onSave, onCancel }: {
  readonly message: ChatMessage;
  readonly busy: boolean;
  readonly onSave: (text: string) => Promise<boolean>;
  readonly onCancel: () => void;
}) {
  const [draft, setDraft] = useState(() => message.parts
    .filter((p) => p.type === "text").map((p) => p.text ?? "").join("\n"));
  const [saving, setSaving] = useState(false);
  const save = async () => {
    if (!draft.trim() || saving || busy) return;
    setSaving(true);
    try {
      await onSave(draft.trim());
    } finally {
      setSaving(false);
    }
  };
  return (
    <li aria-label="You" className="flex flex-col gap-3 rounded-xl border border-zinc-300 bg-zinc-50 p-4 dark:border-zinc-700 dark:bg-zinc-900">
      <textarea
        aria-label="Edit message"
        autoFocus
        rows={4}
        maxLength={100000}
        value={draft}
        disabled={saving}
        onChange={(e) => setDraft(e.currentTarget.value)}
        className="w-full resize-y bg-transparent text-sm outline-none"
      />
      <p className="text-xs text-zinc-500">
        This replaces this message and all later replies. Project files stay as they are.
      </p>
      <div className="flex flex-wrap justify-end gap-2">
        <Button label="Cancel" variant="secondary" disabled={saving} onClick={onCancel} />
        <Button label="Save & regenerate" busy={saving} disabled={busy || !draft.trim()} onClick={() => void save()} />
      </div>
    </li>
  );
}

export function ChatMessageView({ message, busy = false, onEdit }: {
  readonly message: ChatMessage;
  readonly busy?: boolean;
  readonly onEdit?: () => void;
}) {
  if (message.role === "user") {
    return (
      <li aria-label="You" className="group flex items-start justify-end gap-2">
        {onEdit && <IconButton label="Edit message" icon={Pencil} disabled={busy}
          className="shrink-0" onClick={onEdit} />}
        <div className="min-w-0 max-w-[85%] [overflow-wrap:anywhere] rounded-2xl sm:max-w-[80%] rounded-br-sm bg-zinc-900 px-4 py-2 text-sm text-white dark:bg-zinc-100 dark:text-zinc-900">
          {message.parts.map((part) => (
            <Part key={part.id} part={part} markdown={false} />
          ))}
        </div>
      </li>
    );
  }
  return (
    <li aria-label="Agent" className="flex min-w-0 flex-col gap-2 text-sm [overflow-wrap:anywhere]">
      {message.parts.map((part) => (
        <Part key={part.id} part={part} markdown />
      ))}
    </li>
  );
}

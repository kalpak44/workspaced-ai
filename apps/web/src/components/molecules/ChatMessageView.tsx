import { CircleAlert, CircleCheck, Wrench } from "lucide-react";
import type { ChatMessage, ChatPart } from "../../hooks/useChat";
import { Spinner } from "../atoms";
import { Markdown } from "./Markdown";

function ToolPart({ part }: { readonly part: ChatPart }) {
  let state;
  if (part.status === "completed") {
    state = <CircleCheck role="img" aria-label="Done" className="size-3.5 text-emerald-500" />;
  } else if (part.status === "error") {
    state = <CircleAlert role="img" aria-label="Failed" className="size-3.5 text-rose-500" />;
  } else {
    state = <Spinner label="Running" className="size-3.5 text-zinc-400" />;
  }
  return (
    <div className="flex items-center gap-2 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs text-zinc-600 dark:border-zinc-800 dark:text-zinc-400">
      <Wrench aria-hidden className="size-3.5 shrink-0" />
      <span className="font-mono">{part.tool}</span>
      {part.text && <span className="truncate">{part.text}</span>}
      <span className="ml-auto shrink-0">{state}</span>
    </div>
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
export function ChatMessageView({ message }: { readonly message: ChatMessage }) {
  if (message.role === "user") {
    return (
      <li aria-label="You" className="flex justify-end">
        <div className="max-w-[80%] rounded-2xl rounded-br-sm bg-zinc-900 px-4 py-2 text-sm text-white dark:bg-zinc-100 dark:text-zinc-900">
          {message.parts.map((part) => (
            <Part key={part.id} part={part} markdown={false} />
          ))}
        </div>
      </li>
    );
  }
  return (
    <li aria-label="Agent" className="flex flex-col gap-2 text-sm">
      {message.parts.map((part) => (
        <Part key={part.id} part={part} markdown />
      ))}
    </li>
  );
}

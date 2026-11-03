import { CircleAlert, CircleCheck, Wrench } from "lucide-react";
import type { ChatMessage, ChatPart } from "../../hooks/useChat";
import { Spinner } from "../atoms";

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

function Part({ part }: { readonly part: ChatPart }) {
  if (part.type === "tool") {
    return <ToolPart part={part} />;
  }
  if (part.type === "reasoning") {
    return part.text ? (
      <p className="border-l-2 border-zinc-200 pl-3 text-xs whitespace-pre-wrap text-zinc-500 italic dark:border-zinc-700 dark:text-zinc-400">
        {part.text}
      </p>
    ) : null;
  }
  return part.text ? <p className="whitespace-pre-wrap">{part.text}</p> : null;
}

/** The user's messages as bubbles on the right; the agent's in full width, as a transcript. */
export function ChatMessageView({ message }: { readonly message: ChatMessage }) {
  if (message.role === "user") {
    return (
      <li aria-label="You" className="flex justify-end">
        <div className="max-w-[80%] rounded-2xl rounded-br-sm bg-zinc-900 px-4 py-2 text-sm text-white dark:bg-zinc-100 dark:text-zinc-900">
          {message.parts.map((part) => (
            <Part key={part.id} part={part} />
          ))}
        </div>
      </li>
    );
  }
  return (
    <li aria-label="Agent" className="flex flex-col gap-2 text-sm leading-relaxed">
      {message.parts.map((part) => (
        <Part key={part.id} part={part} />
      ))}
    </li>
  );
}

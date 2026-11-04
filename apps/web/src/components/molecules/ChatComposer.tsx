import { Mic, MicOff, SendHorizontal, Square } from "lucide-react";
import { type KeyboardEvent, type ReactNode, type SubmitEvent, useEffect, useRef, useState } from "react";
import { useDictation } from "../../hooks/useDictation";
import { IconButton } from "../atoms";

// The API refuses anything longer.
const MAX_TEXT = 100000;

interface Props {
  /** While the agent replies, the send button becomes a stop button. */
  readonly busy: boolean;
  readonly disabled?: boolean;
  readonly placeholder?: string;
  /** Focus on mount, for a page whose whole point is typing. */
  readonly autoFocus?: boolean;
  readonly modelControl?: ReactNode;
  /** Resolves true once accepted; the text is kept on false so nothing typed is lost. */
  readonly onSend: (text: string) => Promise<boolean>;
  readonly onStop: () => void;
}

/** Enter sends and Shift+Enter starts a new line, as in most chat apps. */
export function ChatComposer({
  busy,
  disabled = false,
  placeholder = "Ask the agent to do something…",
  autoFocus = false,
  modelControl,
  onSend,
  onStop,
}: Props) {
  const [text, setText] = useState("");
  const [pending, setPending] = useState(false);
  const field = useRef<HTMLTextAreaElement>(null);
  const dictation = useDictation((spoken) => {
    setText((current) => `${current}${current && !/\s$/.test(current) ? " " : ""}${spoken}`.slice(0, MAX_TEXT));
    field.current?.focus();
  }, disabled || busy || pending);

  useEffect(() => {
    if (autoFocus) {
      field.current?.focus();
    }
  }, [autoFocus]);

  const submit = async () => {
    const message = text.trim();
    if (!message || disabled || busy || pending || dictation.listening) {
      return;
    }
    setPending(true);
    try {
      if (await onSend(message).catch(() => false)) {
        setText("");
      }
    } finally {
      setPending(false);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  };

  return (
    <>
    <form
      onSubmit={(event: SubmitEvent) => {
        event.preventDefault();
        void submit();
      }}
      className="flex flex-col gap-2 rounded-2xl border border-zinc-300 bg-white p-2 shadow-[0_10px_35px_-18px_rgba(0,0,0,0.35)] transition focus-within:border-zinc-500 focus-within:shadow-[0_14px_40px_-18px_rgba(0,0,0,0.4)] dark:border-zinc-700 dark:bg-zinc-900 dark:focus-within:border-zinc-500"
    >
      {dictation.listening && (
        <div role="status" className="flex items-start gap-2 rounded-xl bg-rose-50 px-3 py-2 text-sm dark:bg-rose-950/40">
          <span className="relative mt-1.5 flex size-2.5 shrink-0">
            <span className="absolute inline-flex size-full animate-ping rounded-full bg-rose-400 opacity-75" />
            <span className="relative inline-flex size-2.5 rounded-full bg-rose-500" />
          </span>
          <span className="shrink-0 font-medium tabular-nums text-rose-600">
            {Math.floor(dictation.seconds / 60)}:{String(dictation.seconds % 60).padStart(2, "0")}
          </span>
          <span className={`min-w-0 flex-1 break-words ${dictation.transcript ? "text-zinc-700 dark:text-zinc-200" : "text-zinc-500"}`}>
            {dictation.transcript || "Listening… Stop to add what you said to your message."}
          </span>
        </div>
      )}
      <textarea
        ref={field}
        aria-label="Message"
        rows={2}
        value={text}
        maxLength={MAX_TEXT}
        disabled={disabled}
        placeholder={placeholder}
        onChange={(e) => setText(e.currentTarget.value)}
        onKeyDown={onKeyDown}
        className="max-h-48 w-full resize-none bg-transparent px-2 py-1 text-sm outline-none disabled:cursor-not-allowed"
      />
      <div className="flex min-w-0 items-center justify-end gap-1">
      {modelControl}
      {dictation.supported && <IconButton
        label={dictation.listening ? "Stop dictation" : "Dictate message"}
        title={dictation.listening ? "Stop dictation" : "Dictate message"}
        icon={dictation.listening ? MicOff : Mic} aria-pressed={dictation.listening}
        disabled={disabled || busy || pending} onClick={dictation.toggle}
        className={dictation.listening ? "bg-rose-100 text-rose-600 dark:bg-rose-950" : ""}
      />}
      {busy ? (
        <IconButton label="Stop" icon={Square} onClick={onStop} />
      ) : (
        <IconButton
          label="Send"
          icon={SendHorizontal}
          submit
          disabled={disabled || pending || dictation.listening || !text.trim()}
        />
      )}
      </div>
    </form>
    {dictation.error && <p role="alert" className="mt-2 text-xs text-rose-600">{dictation.error}</p>}
    </>
  );
}

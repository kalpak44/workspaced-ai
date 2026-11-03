import { SendHorizontal, Square } from "lucide-react";
import { type KeyboardEvent, type SubmitEvent, useState } from "react";
import { IconButton } from "../atoms";

// The API refuses anything longer.
const MAX_TEXT = 100000;

interface Props {
  /** While the agent replies, the send button becomes a stop button. */
  readonly busy: boolean;
  readonly disabled?: boolean;
  /** Resolves true once accepted; the text is kept on false so nothing typed is lost. */
  readonly onSend: (text: string) => Promise<boolean>;
  readonly onStop: () => void;
}

/** Enter sends and Shift+Enter starts a new line, as in most chat apps. */
export function ChatComposer({ busy, disabled = false, onSend, onStop }: Props) {
  const [text, setText] = useState("");
  const [pending, setPending] = useState(false);

  const submit = async () => {
    const message = text.trim();
    if (!message || busy || pending) {
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
    <form
      onSubmit={(event: SubmitEvent) => {
        event.preventDefault();
        void submit();
      }}
      className="flex items-end gap-2 rounded-xl border border-zinc-300 bg-white p-2 shadow-sm focus-within:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:focus-within:border-zinc-500"
    >
      <textarea
        aria-label="Message"
        rows={2}
        value={text}
        maxLength={MAX_TEXT}
        disabled={disabled}
        placeholder="Ask the agent to do something…"
        onChange={(e) => setText(e.currentTarget.value)}
        onKeyDown={onKeyDown}
        className="max-h-48 flex-1 resize-none bg-transparent px-2 py-1 text-sm outline-none disabled:cursor-not-allowed"
      />
      {busy ? (
        <IconButton label="Stop" icon={Square} onClick={onStop} />
      ) : (
        <IconButton
          label="Send"
          icon={SendHorizontal}
          submit
          disabled={disabled || pending || !text.trim()}
        />
      )}
    </form>
  );
}

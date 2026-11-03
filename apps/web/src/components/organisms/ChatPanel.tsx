import { MessageSquare, SendHorizontal } from "lucide-react";
import { IconButton } from "../atoms";

/**
 * The conversation with a workspace's agent. Nothing answers yet, so the composer is shown
 * disabled rather than accepting messages that would go nowhere.
 */
export function ChatPanel() {
  return (
    <section aria-label="Chat" className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
        <MessageSquare aria-hidden className="mb-2 size-8 text-zinc-400" />
        <h3 className="text-2xl font-semibold">Start a conversation</h3>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          Messages with this workspace's agent will appear here
        </p>
      </div>
      <form
        onSubmit={(e) => e.preventDefault()}
        className="m-4 flex items-end gap-2 rounded-lg border border-zinc-300 p-2 dark:border-zinc-700"
      >
        <textarea
          aria-label="Message"
          rows={2}
          disabled
          placeholder="Chat is not connected to the workspace yet"
          className="flex-1 resize-none bg-transparent px-2 py-1 text-sm outline-none disabled:cursor-not-allowed"
        />
        <IconButton label="Send" icon={SendHorizontal} disabled />
      </form>
    </section>
  );
}

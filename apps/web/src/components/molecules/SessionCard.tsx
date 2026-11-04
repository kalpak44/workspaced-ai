import { MessageSquare, Trash2 } from "lucide-react";
import type { Session } from "../../hooks/useSessions";
import { timeAgo } from "../../lib/time";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly session: Session;
  readonly deleting: boolean;
  readonly onOpen: () => void;
  readonly onDelete: () => void;
}

export function SessionCard({ session, deleting, onOpen, onDelete }: Props) {
  const title = session.title ?? "New session";
  return (
    <li className="group relative flex items-center rounded-xl border border-zinc-200/80 bg-white transition duration-150 hover:-translate-y-px hover:border-zinc-300 hover:shadow-sm dark:border-zinc-800 dark:bg-zinc-900/40 dark:hover:border-zinc-700">
      <button
        type="button"
        onClick={onOpen}
        disabled={session.available === false}
        title={session.path}
        className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left"
      >
        <MessageSquare aria-hidden className="size-4 shrink-0 text-zinc-400" />
        <span className="min-w-0 flex-1">
          <span
            className={`block truncate text-sm font-medium ${session.title ? "" : "text-zinc-500 italic dark:text-zinc-400"}`}
          >
            {title}
          </span>
          <span className="block text-xs text-zinc-500 dark:text-zinc-400">
            {session.available === false ? "Conversation unavailable" : timeAgo(session.createdAt)}
          </span>
        </span>
      </button>
      <span className="pr-3">
        {deleting ? (
          <Spinner label={`Deleting ${title}`} className="size-4 text-zinc-400" />
        ) : (
          <IconButton
            label={`Delete ${title}`}
            icon={Trash2}
            onClick={onDelete}
            className="opacity-0 group-hover:opacity-100 focus:opacity-100"
          />
        )}
      </span>
    </li>
  );
}

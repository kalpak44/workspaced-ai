import { CalendarClock, Check, MessageSquare, Trash2 } from "lucide-react";
import type { Session } from "../../hooks/useSessions";
import { timeAgo } from "../../lib/time";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly session: Session;
  readonly deleting: boolean;
  readonly onOpen: () => void;
  readonly onDelete: () => void;
  /** While choosing sessions to delete: whether this one is chosen. Undefined otherwise. */
  readonly selected?: boolean;
  readonly onToggle?: () => void;
}

export function SessionCard({ session, deleting, onOpen, onDelete, selected, onToggle }: Props) {
  const title = session.title ?? "New session";
  const selecting = selected !== undefined;
  return (
    <li className="group relative flex items-center transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/50">
      <button
        type="button"
        onClick={selecting ? onToggle : onOpen}
        // An unavailable conversation cannot be opened, but it can still be chosen for deletion.
        disabled={(session.available === false && !selecting) || (selecting && deleting)}
        title={session.path}
        role={selecting ? "checkbox" : undefined}
        aria-checked={selecting ? selected : undefined}
        className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left"
      >
        {selecting ? (
          <span aria-hidden className={`flex size-4 shrink-0 items-center justify-center rounded border ${selected ? "border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900" : "border-zinc-300 dark:border-zinc-600"}`}>
            {selected && <Check className="size-3" />}
          </span>
        ) : session.task ? (
          <CalendarClock aria-hidden className="size-4 shrink-0 text-zinc-400" />
        ) : (
          <MessageSquare aria-hidden className="size-4 shrink-0 text-zinc-400" />
        )}
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
        ) : selecting ? null : (
          <IconButton
            label={`Delete ${title}`}
            icon={Trash2}
            onClick={onDelete}
            className="opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100"
          />
        )}
      </span>
    </li>
  );
}

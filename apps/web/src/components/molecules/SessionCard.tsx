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
    <li className="group relative flex items-center transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800/40">
      <button
        type="button"
        onClick={selecting ? onToggle : onOpen}
        // An unavailable conversation cannot be opened, but it can still be chosen for deletion.
        disabled={(session.available === false && !selecting) || (selecting && deleting)}
        title={session.path}
        role={selecting ? "checkbox" : undefined}
        aria-checked={selecting ? selected : undefined}
        className="flex min-w-0 flex-1 items-center gap-3 px-4 py-3 text-left disabled:cursor-not-allowed disabled:opacity-60"
      >
        {selecting ? (
          <span aria-hidden className="flex size-8 shrink-0 items-center justify-center">
            <span className={`flex size-4 items-center justify-center rounded border transition-colors ${selected ? "border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900" : "border-zinc-300 bg-white dark:border-zinc-600 dark:bg-zinc-900"}`}>
              {selected && <Check className="size-3" />}
            </span>
          </span>
        ) : (
          <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-500 transition-colors group-hover:bg-zinc-200/70 group-hover:text-zinc-700 dark:bg-zinc-800 dark:text-zinc-400 dark:group-hover:bg-zinc-700/70 dark:group-hover:text-zinc-200">
            {session.task ? <CalendarClock className="size-4" /> : <MessageSquare className="size-4" />}
          </span>
        )}
        <span className="min-w-0 flex-1">
          <span
            className={`block truncate text-sm font-medium ${session.title ? "" : "text-zinc-500 italic dark:text-zinc-400"}`}
          >
            {title}
          </span>
          <span className="mt-0.5 block text-xs text-zinc-500 dark:text-zinc-400">
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
            className="opacity-100 hover:text-rose-600 sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100 dark:hover:text-rose-400"
          />
        )}
      </span>
    </li>
  );
}

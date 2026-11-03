import { Trash2 } from "lucide-react";
import type { Session } from "../../hooks/useSessions";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly session: Session;
  readonly selected: boolean;
  readonly deleting: boolean;
  readonly onSelect: () => void;
  readonly onDelete: () => void;
}

export function SessionItem({ session, selected, deleting, onSelect, onDelete }: Props) {
  const title = session.title ?? "New session";
  return (
    <li
      className={`group flex items-center rounded-md ${
        selected
          ? "bg-zinc-100 font-medium dark:bg-zinc-800"
          : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"
      }`}
    >
      <button
        type="button"
        aria-current={selected ? "page" : undefined}
        onClick={onSelect}
        className="min-w-0 flex-1 truncate px-2 py-1.5 text-left"
      >
        {title}
      </button>
      {deleting ? (
        <Spinner label={`Deleting ${title}`} className="mr-2 size-3.5 shrink-0 text-zinc-400" />
      ) : (
        <IconButton
          label={`Delete ${title}`}
          icon={Trash2}
          onClick={onDelete}
          className="mr-1 p-1 opacity-0 group-hover:opacity-100 focus:opacity-100"
        />
      )}
    </li>
  );
}

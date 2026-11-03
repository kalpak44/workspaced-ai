import { CircleAlert, LoaderCircle, MoreHorizontal } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useDismiss } from "../useDismiss";
import type { Workspace } from "../useWorkspaces";

interface Props {
  readonly workspace: Workspace;
  readonly selected: boolean;
  readonly onSelect: () => void;
  readonly onRename: (name: string) => void;
  readonly onDelete: () => void;
}

export function WorkspaceItem({ workspace, selected, onSelect, onRename, onDelete }: Props) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const ref = useRef<HTMLLIElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const closeMenu = useCallback(() => setMenuOpen(false), []);
  useDismiss(ref, menuOpen, closeMenu);

  // Focus follows the user's own Rename click, so it is moved here rather than by autoFocus.
  useEffect(() => {
    if (editing && input.current) {
      input.current.focus();
      input.current.select();
    }
  }, [editing]);

  if (editing) {
    return (
      <li>
        <input
          aria-label="Workspace name"
          ref={input}
          defaultValue={workspace.name}
          onBlur={(e) => {
            onRename(e.currentTarget.value);
            setEditing(false);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.currentTarget.blur();
            } else if (e.key === "Escape") {
              setEditing(false);
            }
          }}
          className="w-full rounded-md border border-zinc-300 bg-transparent px-2 py-1.5 outline-none dark:border-zinc-600"
        />
      </li>
    );
  }

  return (
    <li
      ref={ref}
      className={`group relative flex items-center rounded-md ${
        selected
          ? "bg-zinc-100 font-medium dark:bg-zinc-800"
          : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"
      }`}
    >
      <button
        type="button"
        aria-current={selected ? "page" : undefined}
        onClick={onSelect}
        className="flex min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left"
      >
        <span className="truncate">{workspace.name}</span>
        {workspace.status === "INITIALIZING" && (
          <LoaderCircle
            role="status"
            aria-label="Initializing"
            className="size-3.5 shrink-0 animate-spin text-zinc-400"
          />
        )}
        {workspace.status === "FAILED" && (
          <CircleAlert role="status" aria-label="Failed" className="size-3.5 shrink-0 text-rose-500" />
        )}
      </button>
      <button
        type="button"
        aria-label={`Actions for ${workspace.name}`}
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen(!menuOpen)}
        className="mr-1 rounded p-1 text-zinc-400 opacity-0 group-hover:opacity-100 hover:text-zinc-600 aria-expanded:opacity-100 dark:hover:text-zinc-200"
      >
        <MoreHorizontal className="size-4" />
      </button>
      {menuOpen && (
        <div
          role="menu"
          className="absolute top-full right-0 z-10 mt-1 w-36 overflow-hidden rounded-md border border-zinc-200 bg-white py-1 text-sm shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setMenuOpen(false);
              setEditing(true);
            }}
            className="w-full px-3 py-1.5 text-left hover:bg-zinc-100 dark:hover:bg-zinc-800"
          >
            Rename
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setMenuOpen(false);
              onDelete();
            }}
            className="w-full px-3 py-1.5 text-left text-rose-600 hover:bg-zinc-100 dark:text-rose-400 dark:hover:bg-zinc-800"
          >
            Delete
          </button>
        </div>
      )}
    </li>
  );
}

import { MoreHorizontal, Pencil, Settings, SquareTerminal, Trash2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import type { Workspace } from "../../hooks/useWorkspaces";
import { IconButton, Spinner } from "../atoms";
import { Menu, MenuItem } from "./Menu";
import { WorkspaceStatusIcon } from "./WorkspaceStatusIcon";

interface Props {
  readonly workspace: Workspace;
  readonly selected: boolean;
  /** While its delete is in flight: a spinner in place of the status, and no actions. */
  readonly deleting?: boolean;
  readonly onSelect: () => void;
  readonly onRename: (name: string) => void;
  readonly onSettings: () => void;
  readonly onTerminal: () => void;
  readonly onDelete: () => void;
}

export function WorkspaceItem({
  workspace,
  selected,
  deleting = false,
  onSelect,
  onRename,
  onSettings,
  onTerminal,
  onDelete,
}: Props) {
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
          ref={input}
          aria-label="Workspace name"
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
        {deleting ? (
          <Spinner label="Deleting" className="size-3.5 shrink-0 text-zinc-400" />
        ) : (
          <WorkspaceStatusIcon status={workspace.status} />
        )}
      </button>
      <IconButton
        label={`Actions for ${workspace.name}`}
        icon={MoreHorizontal}
        aria-expanded={menuOpen}
        disabled={deleting}
        onClick={() => setMenuOpen(!menuOpen)}
        className="mr-1 p-1 opacity-0 group-hover:opacity-100 aria-expanded:opacity-100"
      />
      {menuOpen && (
        <Menu className="mt-1 w-36 rounded-md py-1">
          <MenuItem
            className="gap-2 px-3 py-2"
            onClick={() => {
              setMenuOpen(false);
              setEditing(true);
            }}
          >
            <span className="flex items-center gap-2">
              <Pencil aria-hidden className="size-3.5" />
              Rename
            </span>
          </MenuItem>
          <MenuItem
            className="gap-2 px-3 py-2"
            onClick={() => {
              setMenuOpen(false);
              onSettings();
            }}
          >
            <span className="flex items-center gap-2">
              <Settings aria-hidden className="size-3.5" />
              Settings
            </span>
          </MenuItem>
          <MenuItem
            className="gap-2 px-3 py-2"
            onClick={() => {
              setMenuOpen(false);
              onTerminal();
            }}
          >
            <span className="flex items-center gap-2">
              <SquareTerminal aria-hidden className="size-3.5" />
              Terminal
            </span>
          </MenuItem>
          <div className="mx-2 my-1 border-t border-zinc-200 dark:border-zinc-700" />
          <MenuItem
            danger
            className="gap-2 px-3 py-2"
            onClick={() => {
              setMenuOpen(false);
              onDelete();
            }}
          >
            <span className="flex items-center gap-2">
              <Trash2 aria-hidden className="size-3.5" />
              Delete workspace
            </span>
          </MenuItem>
        </Menu>
      )}
    </li>
  );
}

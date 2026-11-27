import { Activity, LogOut, type LucideIcon, MoreHorizontal, Pencil, RotateCw, Settings, SquareTerminal, Trash2, UserPlus } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useIsOwner, useIsShared } from "../../hooks/useCollaborators";
import { useDismiss } from "../../hooks/useDismiss";
import { useIsMobile } from "../../hooks/useIsMobile";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { IconButton } from "../atoms";
import { Menu, MenuItem } from "./Menu";
import { Modal, ModalBody } from "./Modal";

export interface WorkspaceActionHandlers {
  readonly onRename: () => void;
  readonly onSettings: () => void;
  readonly onTerminal: () => void;
  readonly onMonitoring: () => void;
  readonly onRestart: () => void;
  /** Deletes the workspace, or for a collaborator, leaves it. */
  readonly onDelete: () => void;
}

interface Props extends WorkspaceActionHandlers {
  readonly workspace: Workspace;
  /** While its delete is in flight there is nothing left to act on. */
  readonly deleting?: boolean;
  readonly className?: string;
}

/**
 * The "…" menu of one workspace, wherever the workspace is shown. Phones get the same actions
 * in a bottom sheet, which is easier to hit than a small popover.
 */
export function WorkspaceActions({ workspace, deleting = false, className = "", ...handlers }: Props) {
  const [open, setOpen] = useState(false);
  const mobile = useIsMobile();
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open && !mobile, close);
  // A VM that is still starting, or paused, has no shell to open, nothing to measure and nothing
  // to restart.
  const starting = workspace.status === "INITIALIZING" || workspace.status === "PAUSED";
  // Only its owner can end a workspace; anyone else on it can only leave.
  const shared = useIsShared(workspace);
  const owner = useIsOwner(workspace);

  const item = (label: string, Icon: LucideIcon, run: () => void, disabled = false, danger = false) => (
    <MenuItem className="gap-2 rounded-md px-3 py-2" danger={danger} disabled={disabled} onClick={() => { setOpen(false); run(); }}>
      <span className="flex items-center gap-2"><Icon aria-hidden className="size-3.5" />{label}</span>
    </MenuItem>
  );
  const actions = <>
    {item("Rename", Pencil, handlers.onRename)}
    {item("Settings", Settings, handlers.onSettings)}
    {item("Terminal", SquareTerminal, handlers.onTerminal, starting)}
    {item("Monitoring", Activity, handlers.onMonitoring, starting)}
    {item("Restart", RotateCw, handlers.onRestart, starting)}
    {/* A free demo is its owner's trial and cannot be shared. */}
    {!workspace.demo && item("Invite people", UserPlus, () => navigate({ name: "settings", workspaceId: workspace.id, section: "collaborators" }))}
    {(shared || owner) && <div className="mx-2 my-1 border-t border-zinc-200 dark:border-zinc-700" />}
    {shared && item("Leave workspace", LogOut, handlers.onDelete, false, true)}
    {owner && item("Delete workspace", Trash2, handlers.onDelete, false, true)}
  </>;

  return (
    <div ref={ref} className={`relative ${className}`}>
      <IconButton label={`Actions for ${workspace.name}`} title="Workspace actions" icon={MoreHorizontal} aria-expanded={open}
        aria-haspopup={mobile ? "dialog" : "menu"} disabled={deleting} onClick={() => setOpen(!open)} />
      {open && (mobile ? (
        <Modal title={workspace.name} description="Workspace actions" onClose={close}>
          <ModalBody><div role="menu" aria-label="Workspace actions" className="space-y-1">{actions}</div></ModalBody>
        </Modal>
      ) : (
        <Menu className="mt-1 w-48 rounded-xl p-1">{actions}</Menu>
      ))}
    </div>
  );
}

/** The workspace's name as an input, after Rename; Enter or leaving saves, Escape cancels. */
export function WorkspaceNameInput({ name, onDone, className = "" }: { readonly name: string; readonly onDone: (name: string | null) => void; readonly className?: string }) {
  return (
    <input
      // Focus follows the user's own Rename click.
      ref={(input) => { if (input && document.activeElement !== input) { input.focus(); input.select(); } }}
      aria-label="Workspace name"
      defaultValue={name}
      onBlur={(event) => onDone(event.currentTarget.value)}
      onKeyDown={(event) => {
        if (event.key === "Enter") event.currentTarget.blur();
        else if (event.key === "Escape") { event.preventDefault(); onDone(null); }
      }}
      className={`field min-w-0 px-2.5 py-1.5 text-sm font-semibold ${className}`}
    />
  );
}

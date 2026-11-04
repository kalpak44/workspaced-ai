import { Plus } from "lucide-react";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button } from "../atoms";
import { WorkspaceItem } from "../molecules";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly selectedId: string | null;
  readonly deleting: ReadonlySet<string>;
  readonly onSelect: (id: string) => void;
  readonly onNew: () => void;
  readonly onRename: (id: string, name: string) => void;
  readonly onSettings: (id: string) => void;
  readonly onTerminal: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

/** The persistent workspace switcher and home for workspace-level actions. */
export function Sidebar({
  workspaces,
  selectedId,
  deleting,
  onSelect,
  onNew,
  onRename,
  onSettings,
  onTerminal,
  onDelete,
}: Props) {
  return (
    <aside className="flex w-64 shrink-0 flex-col border-r border-zinc-200 dark:border-zinc-800">
      <ul aria-label="Workspaces" className="min-h-0 flex-1 space-y-0.5 overflow-y-auto p-3 text-sm">
        {workspaces.map((workspace) => (
          <WorkspaceItem
            key={workspace.id}
            workspace={workspace}
            selected={workspace.id === selectedId}
            deleting={deleting.has(workspace.id)}
            onSelect={() => onSelect(workspace.id)}
            onRename={(name) => onRename(workspace.id, name)}
            onSettings={() => onSettings(workspace.id)}
            onTerminal={() => onTerminal(workspace.id)}
            onDelete={() => onDelete(workspace.id)}
          />
        ))}
      </ul>
      <div className="shrink-0 border-t border-zinc-200 p-3 dark:border-zinc-800 [&>button]:w-full [&>button]:justify-center">
        <Button label="New workspace" icon={Plus} variant="secondary" onClick={onNew} />
      </div>
    </aside>
  );
}

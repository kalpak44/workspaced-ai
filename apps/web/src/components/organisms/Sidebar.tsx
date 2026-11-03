import { Plus } from "lucide-react";
import type { Workspace } from "../../hooks/useWorkspaces";
import { IconButton } from "../atoms";
import { WorkspaceItem } from "../molecules";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly selectedId: string | null;
  readonly deleting: ReadonlySet<string>;
  readonly onSelect: (id: string) => void;
  readonly onNew: () => void;
  readonly onRename: (id: string, name: string) => void;
  readonly onDelete: (id: string) => void;
}

/** Its header shares the page header's height and rule, so the two read as one bar. */
export function Sidebar({ workspaces, selectedId, deleting, onSelect, onNew, onRename, onDelete }: Props) {
  return (
    <aside className="flex w-64 shrink-0 flex-col border-r border-zinc-200 dark:border-zinc-800">
      <div className="flex h-14 shrink-0 items-center justify-between border-b border-zinc-200 pr-3 pl-5 dark:border-zinc-800">
        <h2 className="text-sm font-medium">Workspaces</h2>
        <IconButton label="New workspace" icon={Plus} onClick={onNew} />
      </div>
      <ul className="flex-1 space-y-0.5 overflow-y-auto p-3 text-sm">
        {workspaces.map((workspace) => (
          <WorkspaceItem
            key={workspace.id}
            workspace={workspace}
            selected={workspace.id === selectedId}
            deleting={deleting.has(workspace.id)}
            onSelect={() => onSelect(workspace.id)}
            onRename={(name) => onRename(workspace.id, name)}
            onDelete={() => onDelete(workspace.id)}
          />
        ))}
      </ul>
    </aside>
  );
}

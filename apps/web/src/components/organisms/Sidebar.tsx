import { FileText, Library, Plus, Upload } from "lucide-react";
import { useRef } from "react";
import type { Workspace } from "../../hooks/useWorkspaces";
import { IconButton } from "../atoms";
import { NavItem, WorkspaceItem } from "../molecules";

export type Section = "workspaces" | "knowledge" | "files";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly selectedId: string | null;
  readonly section: Section;
  readonly onSection: (section: Section) => void;
  readonly onSelect: (id: string) => void;
  /** Asks for a new workspace; `name` suggests what to call it. */
  readonly onNew: (name?: string) => void;
  readonly onRename: (id: string, name: string) => void;
  readonly onDelete: (id: string) => void;
}

export function Sidebar(props: Props) {
  const { workspaces, selectedId, section, onSection, onSelect, onNew } = props;
  const fileInput = useRef<HTMLInputElement>(null);

  return (
    <aside className="flex w-72 shrink-0 flex-col border-r border-zinc-200 dark:border-zinc-800">
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <h2 className="font-medium">Workspaces</h2>
        <div className="flex gap-1">
          <IconButton
            label="Import workspace"
            icon={Upload}
            onClick={() => fileInput.current?.click()}
          />
          <IconButton
            label="New workspace"
            icon={Plus}
            onClick={() => {
              onNew();
              onSection("workspaces");
            }}
          />
          {/* Takes only the file name, as the suggested name, until workspaces carry content. */}
          <input
            ref={fileInput}
            type="file"
            accept=".json"
            hidden
            data-testid="import-input"
            onChange={(e) => {
              const file = e.currentTarget.files?.[0];
              if (file) {
                onNew(file.name.replace(/\.json$/i, ""));
                onSection("workspaces");
              }
              e.currentTarget.value = "";
            }}
          />
        </div>
      </div>
      <ul className="flex-1 space-y-0.5 overflow-y-auto px-4 text-sm">
        {workspaces.map((workspace) => (
          <WorkspaceItem
            key={workspace.id}
            workspace={workspace}
            selected={section === "workspaces" && workspace.id === selectedId}
            onSelect={() => {
              onSelect(workspace.id);
              onSection("workspaces");
            }}
            onRename={(name) => props.onRename(workspace.id, name)}
            onDelete={() => props.onDelete(workspace.id)}
          />
        ))}
      </ul>
      <nav className="space-y-0.5 border-t border-zinc-200 p-4 text-sm dark:border-zinc-800">
        <NavItem
          label="Knowledge"
          icon={Library}
          active={section === "knowledge"}
          onClick={() => onSection("knowledge")}
        />
        <NavItem
          label="My Files"
          icon={FileText}
          active={section === "files"}
          onClick={() => onSection("files")}
        />
      </nav>
    </aside>
  );
}

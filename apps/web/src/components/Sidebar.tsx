import { FileText, Library, Plus, Upload } from "lucide-react";
import { useRef } from "react";
import type { Workspace } from "../useWorkspaces";
import { WorkspaceItem } from "./WorkspaceItem";

export type Section = "workspaces" | "knowledge" | "files";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly selectedId: string | null;
  readonly section: Section;
  readonly onSection: (section: Section) => void;
  readonly onSelect: (id: string) => void;
  readonly onCreate: (name?: string) => void;
  readonly onRename: (id: string, name: string) => void;
  readonly onDelete: (id: string) => void;
}

const ICON_BUTTON =
  "rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";

export function Sidebar(props: Props) {
  const { workspaces, selectedId, section, onSection, onSelect, onCreate } = props;
  const fileInput = useRef<HTMLInputElement>(null);

  const navClass = (target: Section) =>
    `flex w-full items-center gap-2 rounded-md px-2 py-2 text-left ${
      section === target
        ? "bg-zinc-100 font-medium dark:bg-zinc-800"
        : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"
    }`;

  return (
    <aside className="flex w-72 shrink-0 flex-col border-r border-zinc-200 dark:border-zinc-800">
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <h2 className="font-medium">Workspaces</h2>
        <div className="flex gap-1">
          <button
            type="button"
            aria-label="Import workspace"
            onClick={() => fileInput.current?.click()}
            className={ICON_BUTTON}
          >
            <Upload className="size-4" />
          </button>
          <button
            type="button"
            aria-label="New workspace"
            onClick={() => {
              onCreate();
              onSection("workspaces");
            }}
            className={ICON_BUTTON}
          >
            <Plus className="size-4" />
          </button>
          {/* Imports a workspace by file name only, until workspaces carry content. */}
          <input
            ref={fileInput}
            type="file"
            accept=".json"
            hidden
            data-testid="import-input"
            onChange={(e) => {
              const file = e.currentTarget.files?.[0];
              if (file) {
                onCreate(file.name.replace(/\.json$/i, ""));
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
        <button type="button" onClick={() => onSection("knowledge")} className={navClass("knowledge")}>
          <Library className="size-4" />
          Knowledge
        </button>
        <button type="button" onClick={() => onSection("files")} className={navClass("files")}>
          <FileText className="size-4" />
          My Files
        </button>
      </nav>
    </aside>
  );
}

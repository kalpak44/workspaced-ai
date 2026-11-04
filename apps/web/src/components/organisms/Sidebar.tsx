import { ChevronDown, Layers, Plus, Search } from "lucide-react";
import { useCallback, useId, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
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
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const root = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const listId = useId();
  const close = useCallback(() => { setOpen(false); setQuery(""); }, []);
  useDismiss(root, open, close);
  const selected = workspaces.find((workspace) => workspace.id === selectedId);
  const filtered = workspaces.filter((workspace) => workspace.name.toLowerCase().includes(query.trim().toLowerCase()));

  return (
    <aside ref={root} className="relative z-20 grid w-full min-h-0 shrink-0 grid-cols-[minmax(0,1fr)_auto] border-b border-zinc-200 sm:z-auto sm:flex sm:w-56 sm:flex-col sm:border-r sm:border-b-0 lg:w-64 dark:border-zinc-800">
      <div className="relative min-h-0 min-w-0 p-3 sm:flex sm:flex-1 sm:flex-col sm:p-0">
        <button ref={trigger} type="button" aria-label="Choose workspace" aria-expanded={open} aria-controls={listId}
          onClick={() => { setQuery(""); setOpen(!open); }}
          className="flex min-h-11 w-full min-w-0 items-center gap-2 rounded-md border border-zinc-300 px-3 text-sm dark:border-zinc-700 sm:hidden">
          <Layers aria-hidden className="size-4 shrink-0 text-zinc-400" />
          <span className="min-w-0 flex-1 truncate text-left">{selected?.name ?? "Choose workspace"}</span>
          <ChevronDown aria-hidden className="size-4 shrink-0 text-zinc-400" />
        </button>
      <ul id={listId} aria-label="Workspaces" onKeyDown={(event) => {
        if (event.key === "Escape") { close(); trigger.current?.focus(); }
      }} className={`min-h-0 flex-1 space-y-0.5 overflow-y-auto p-3 text-sm ${open ? "" : "max-sm:hidden"} max-sm:absolute max-sm:left-3 max-sm:top-full max-sm:z-20 max-sm:max-h-[50dvh] max-sm:w-[calc(100vw-1.5rem)] max-sm:rounded-lg max-sm:border max-sm:border-zinc-200 max-sm:bg-white max-sm:p-2 max-sm:shadow-xl max-sm:dark:border-zinc-700 max-sm:dark:bg-zinc-900`}>
        {open && <li className="mb-2 sm:hidden">
          <label className="flex items-center gap-2 rounded-md border border-zinc-300 px-3 dark:border-zinc-700">
            <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
            <input type="search" aria-label="Search workspaces" placeholder="Search workspaces…" autoFocus value={query} onChange={(event) => setQuery(event.target.value)} className="min-h-11 w-full min-w-0 bg-transparent outline-none" />
          </label>
        </li>}
        {filtered.map((workspace) => (
          <WorkspaceItem
            key={workspace.id}
            workspace={workspace}
            selected={workspace.id === selectedId}
            deleting={deleting.has(workspace.id)}
            onSelect={() => { close(); onSelect(workspace.id); }}
            onRename={(name) => onRename(workspace.id, name)}
            onSettings={() => { close(); onSettings(workspace.id); }}
            onTerminal={() => { close(); onTerminal(workspace.id); }}
            onDelete={() => { close(); onDelete(workspace.id); }}
          />
        ))}
        {filtered.length === 0 && <li className="px-2 py-3 text-sm text-zinc-500">{query.trim() ? "No matching workspaces." : "No workspaces yet."}</li>}
      </ul>
      </div>
      <div className="shrink-0 p-3 pl-0 sm:border-t sm:border-zinc-200 sm:pl-3 dark:border-zinc-800 [&>button]:w-full [&>button]:justify-center">
        <Button label="New workspace" icon={Plus} variant="secondary" onClick={() => { close(); onNew(); }} />
      </div>
    </aside>
  );
}

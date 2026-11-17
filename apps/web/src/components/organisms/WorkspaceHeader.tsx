import { Activity, Check, ChevronsUpDown, Cpu, LayoutGrid, MessageSquare, Plus, RotateCw, Settings, SquareTerminal } from "lucide-react";
import { type MouseEvent, useCallback, useEffect, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import { navigate, type Route } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { routePath } from "../../lib/routes";
import { Spinner } from "../atoms";
import { Menu, MenuItem, WorkspaceActions, WorkspaceNameInput, WorkspaceStatusIcon } from "../molecules";

export type WorkspaceView = "sessions" | "terminal" | "monitoring" | "settings";

// Switching workspace keeps the view, so comparing two workspaces' metrics is one click.
const VIEW_ROUTE = { sessions: "workspace", terminal: "terminal", monitoring: "monitoring", settings: "settings" } as const;

interface Props {
  readonly workspace: Workspace;
  /** Every workspace, for the switcher. */
  readonly workspaces: readonly Workspace[];
  readonly current: WorkspaceView;
  readonly deleting: boolean;
  readonly onRestart: () => void;
  readonly onRename: (name: string) => void;
  readonly onDelete: () => void;
  readonly onNew: () => void;
}

const STATUS = {
  READY: { label: "Running", dot: "bg-emerald-500", text: "text-emerald-700 dark:text-emerald-400" },
  INITIALIZING: { label: "Starting", dot: "animate-pulse bg-amber-500", text: "text-amber-700 dark:text-amber-400" },
  PAUSED: { label: "Paused", dot: "bg-zinc-400", text: "text-zinc-600 dark:text-zinc-400" },
  FAILED: { label: "Stopped", dot: "bg-rose-500", text: "text-rose-700 dark:text-rose-400" },
} as const;

/**
 * Everything one workspace offers, side by side as tabs, so the terminal, its metrics and its
 * settings are one click from its sessions instead of behind the sidebar's menu.
 */
export function WorkspaceHeader({ workspace, workspaces, current, deleting, onRestart, onRename, onDelete, onNew }: Props) {
  const [editing, setEditing] = useState(false);
  const [switching, setSwitching] = useState(false);
  const switcher = useRef<HTMLDivElement>(null);
  const closeSwitcher = useCallback(() => setSwitching(false), []);
  useDismiss(switcher, switching, closeSwitcher);
  const tabBar = useRef<HTMLElement>(null);

  // On a narrow screen the tabs scroll sideways; the open one is always brought into view.
  useEffect(() => {
    tabBar.current?.querySelector('[aria-current="page"]')?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [current]);
  // Nothing to open a shell on, or to measure, until the VM is up; the same rule as the menu.
  const starting = workspace.status === "INITIALIZING" || workspace.status === "PAUSED";
  const tabs: ReadonlyArray<{ readonly view: WorkspaceView; readonly label: string; readonly icon: typeof Cpu; readonly route: Route; readonly disabled: boolean }> = [
    { view: "sessions", label: "Sessions", icon: MessageSquare, route: { name: "workspace", workspaceId: workspace.id }, disabled: false },
    { view: "terminal", label: "Terminal", icon: SquareTerminal, route: { name: "terminal", workspaceId: workspace.id }, disabled: starting },
    { view: "monitoring", label: "Monitoring", icon: Activity, route: { name: "monitoring", workspaceId: workspace.id }, disabled: starting },
    { view: "settings", label: "Settings", icon: Settings, route: { name: "settings", workspaceId: workspace.id }, disabled: false },
  ];
  const status = deleting ? { label: "Deleting", dot: "animate-pulse bg-zinc-400", text: "text-zinc-500 dark:text-zinc-400" } : STATUS[workspace.status];

  const open = (event: MouseEvent<HTMLAnchorElement>, route: Route) => {
    // Modified clicks keep the browser's own behaviour, such as opening a new tab.
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    navigate(route);
  };

  return (
    <section aria-label="Workspace details" className="shrink-0">
      {/* The same column as every page under it, so switching tabs moves nothing; part of the
          page rather than a second bar under the app's own. */}
      <div className="mx-auto w-full max-w-5xl px-4 pt-5 sm:px-6 sm:pt-6">
      <div className="flex flex-wrap items-end gap-x-6 border-b border-zinc-200 lg:flex-nowrap dark:border-zinc-800">
      <div className="order-1 flex min-w-0 flex-1 items-center gap-3 pb-3 lg:flex-none">
        <span aria-hidden className="hidden size-9 shrink-0 items-center justify-center rounded-lg bg-zinc-900 text-sm font-semibold text-white uppercase sm:flex dark:bg-white dark:text-zinc-900">
          {workspace.name.trim().charAt(0) || "W"}
        </span>
        <div className="min-w-0 flex-1">
          {editing ? (
            <WorkspaceNameInput name={workspace.name} className="w-full max-w-sm"
              onDone={(name) => { setEditing(false); if (name !== null) onRename(name); }} />
          ) : (
            <div ref={switcher} className="relative flex min-w-0 items-center gap-1.5">
              <button type="button" aria-label="Switch workspace" aria-expanded={switching} aria-haspopup="menu" onClick={() => setSwitching(!switching)}
                className="-ml-1.5 flex min-w-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-base font-semibold tracking-tight transition-colors hover:bg-zinc-100 dark:hover:bg-zinc-800">
                <span className="truncate">{workspace.name}</span>
                <ChevronsUpDown aria-hidden className="size-3.5 shrink-0 text-zinc-400" />
              </button>
              {deleting ? <Spinner label="Deleting" className="size-3.5 shrink-0 text-zinc-400" /> : <WorkspaceStatusIcon status={workspace.status} />}
              {switching && (
                <Menu className="right-auto! left-0 mt-1 w-72 rounded-xl p-1">
                  <p className="px-3 pt-1.5 pb-1 text-[11px] font-medium tracking-wide text-zinc-400 uppercase">Switch workspace</p>
                  <div className="max-h-72 overflow-y-auto">
                    {workspaces.map((other) => (
                      <MenuItem key={other.id} className="gap-2 rounded-md px-3 py-2"
                        onClick={() => { setSwitching(false); navigate({ name: VIEW_ROUTE[current], workspaceId: other.id }); }}>
                        <span className="flex min-w-0 items-center gap-2">
                          <span aria-hidden className="flex size-6 shrink-0 items-center justify-center rounded-md bg-zinc-100 text-[11px] font-semibold uppercase dark:bg-zinc-800">{other.name.trim().charAt(0) || "W"}</span>
                          <span className="truncate">{other.name}</span>
                        </span>
                        {other.id === workspace.id && <Check aria-hidden className="size-4 shrink-0 text-zinc-500" />}
                      </MenuItem>
                    ))}
                  </div>
                  <div className="mx-2 my-1 border-t border-zinc-200 dark:border-zinc-700" />
                  <MenuItem className="justify-start gap-2 rounded-md px-3 py-2" onClick={() => { setSwitching(false); navigate({ name: "home" }); }}>
                    <LayoutGrid aria-hidden className="size-4 text-zinc-500" />All workspaces
                  </MenuItem>
                  <MenuItem className="justify-start gap-2 rounded-md px-3 py-2" onClick={() => { setSwitching(false); onNew(); }}>
                    <Plus aria-hidden className="size-4 text-zinc-500" />Create a workspace
                  </MenuItem>
                </Menu>
              )}
            </div>
          )}
          <p className="flex items-center gap-2 text-xs text-zinc-500 dark:text-zinc-400">
            <span className={`flex items-center gap-1.5 font-medium ${status.text}`}>
              <span aria-hidden className={`size-1.5 rounded-full ${status.dot}`} />
              {status.label}
            </span>
            {workspace.model && <span className="hidden truncate font-mono sm:inline">· {workspace.model}</span>}
          </p>
        </div>
      </div>
      <div className="order-2 flex shrink-0 items-center gap-2 pb-3 lg:order-3 lg:ml-auto">
        {workspace.restartRequired && !starting && !deleting && (
          <button type="button" onClick={onRestart}
            className="hidden items-center gap-1.5 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-100 sm:flex dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-300 dark:hover:bg-amber-950">
            <RotateCw aria-hidden className="size-3" />Restart to apply changes
          </button>
        )}
        <WorkspaceActions workspace={workspace} deleting={deleting} onRename={() => setEditing(true)} onRestart={onRestart} onDelete={onDelete}
          onSettings={() => navigate({ name: "settings", workspaceId: workspace.id })}
          onTerminal={() => navigate({ name: "terminal", workspaceId: workspace.id })}
          onMonitoring={() => navigate({ name: "monitoring", workspaceId: workspace.id })} />
      </div>
      <nav ref={tabBar} aria-label="Workspace views" className="order-3 -mb-px flex min-w-0 basis-full gap-0.5 overflow-x-auto [scrollbar-width:none] max-lg:-ml-2.5 sm:gap-1 lg:order-2 lg:basis-auto [&::-webkit-scrollbar]:hidden">
        {tabs.map(({ view, label, icon: Icon, route, disabled }) =>
          disabled ? (
            <span key={view} aria-disabled="true" title="Available once the workspace has started"
              className="flex shrink-0 cursor-not-allowed items-center gap-1.5 border-b-2 border-transparent px-2.5 py-2.5 sm:gap-2 sm:px-3 text-sm font-medium text-zinc-300 dark:text-zinc-600">
              <Icon aria-hidden className="size-4 max-sm:hidden" />{label}
            </span>
          ) : (
            <a key={view} href={routePath(route)} aria-current={view === current ? "page" : undefined} onClick={(event) => open(event, route)}
              className={`flex shrink-0 items-center gap-1.5 rounded-t-md border-b-2 px-2.5 py-2.5 sm:gap-2 sm:px-3 text-sm font-medium whitespace-nowrap transition-colors ${
                view === current
                  ? "border-zinc-900 text-zinc-900 dark:border-white dark:text-white"
                  : "border-transparent text-zinc-500 hover:border-zinc-300 hover:text-zinc-900 dark:text-zinc-400 dark:hover:border-zinc-700 dark:hover:text-white"
              }`}>
              <Icon aria-hidden className="size-4 max-sm:hidden" />{label}
            </a>
          ),
        )}
      </nav>
      </div>
      </div>
    </section>
  );
}

import { Activity, ArrowRight, MessageSquare, Settings, SquareTerminal, Users } from "lucide-react";
import { useState } from "react";
import { useIsShared } from "../../hooks/useCollaborators";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Spinner } from "../atoms";
import { WorkspaceActions, type WorkspaceActionHandlers, WorkspaceNameInput } from "./WorkspaceActions";
import { WorkspaceStatusIcon } from "./WorkspaceStatusIcon";

interface Props extends Omit<WorkspaceActionHandlers, "onRename"> {
  readonly workspace: Workspace;
  readonly deleting: boolean;
  readonly onOpen: () => void;
  readonly onRename: (name: string) => void;
}

const STATUS = {
  READY: { label: "Running", tone: "bg-emerald-50 text-emerald-700 ring-emerald-600/15 dark:bg-emerald-950/40 dark:text-emerald-400 dark:ring-emerald-400/20", dot: "bg-emerald-500" },
  INITIALIZING: { label: "Starting", tone: "bg-amber-50 text-amber-700 ring-amber-600/15 dark:bg-amber-950/40 dark:text-amber-400 dark:ring-amber-400/20", dot: "animate-pulse bg-amber-500" },
  PAUSED: { label: "Paused", tone: "bg-zinc-100 text-zinc-700 ring-zinc-500/15 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-white/10", dot: "bg-zinc-400" },
  FAILED: { label: "Stopped", tone: "bg-rose-50 text-rose-700 ring-rose-600/15 dark:bg-rose-950/40 dark:text-rose-400 dark:ring-rose-400/20", dot: "bg-rose-500" },
} as const;

/** One workspace on the overview: what state it is in, and a direct way into each of its tools. */
export function WorkspaceCard({ workspace, deleting, onOpen, onRename, ...handlers }: Props) {
  const [editing, setEditing] = useState(false);
  const shared = useIsShared(workspace);
  const status = deleting
    ? { label: "Deleting", tone: "bg-zinc-100 text-zinc-600 ring-zinc-500/15 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-white/10", dot: "animate-pulse bg-zinc-400" }
    : STATUS[workspace.status];
  const starting = workspace.status === "INITIALIZING" || workspace.status === "PAUSED";
  const quick = [
    { label: "Sessions", icon: MessageSquare, run: onOpen, disabled: false },
    { label: "Terminal", icon: SquareTerminal, run: handlers.onTerminal, disabled: starting },
    { label: "Monitoring", icon: Activity, run: handlers.onMonitoring, disabled: starting },
    { label: "Settings", icon: Settings, run: handlers.onSettings, disabled: false },
  ];

  return (
    // A workspace shared with the user is tinted, so it never reads as one of their own.
    <li data-shared={shared || undefined}
      className={`card group relative flex min-w-0 flex-col transition-all has-[[aria-expanded=true]]:z-30 hover:-translate-y-px hover:shadow-md ${
        shared
          ? "border-sky-200 bg-sky-50/70 hover:border-sky-300 dark:border-sky-900/60 dark:bg-sky-950/25 dark:hover:border-sky-800"
          : "hover:border-zinc-300 dark:hover:border-zinc-700"
      }`}>
      <div className="flex items-start gap-3 p-4 pb-3">
        <span aria-hidden className={`relative flex size-10 shrink-0 items-center justify-center rounded-xl text-sm font-semibold uppercase ${
          shared ? "bg-sky-600 text-white dark:bg-sky-500 dark:text-sky-950" : "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900"
        }`}>
          {workspace.name.trim().charAt(0) || "W"}
          {shared && (
            <span className="absolute -right-1 -bottom-1 flex size-4.5 items-center justify-center rounded-full bg-white text-sky-600 ring-2 ring-sky-50 dark:bg-zinc-900 dark:text-sky-400 dark:ring-sky-950">
              <Users className="size-2.5" />
            </span>
          )}
        </span>
        <div className="min-w-0 flex-1">
          {editing ? (
            <WorkspaceNameInput name={workspace.name} className="w-full"
              onDone={(name) => { setEditing(false); if (name !== null) onRename(name); }} />
          ) : (
            <button type="button" onClick={onOpen} disabled={deleting}
              className="flex max-w-full items-center gap-1.5 text-left font-semibold tracking-tight after:absolute after:inset-0 after:rounded-xl hover:underline hover:underline-offset-4 disabled:cursor-not-allowed">
              <span className="truncate">{workspace.name}</span>
              {deleting ? <Spinner label="Deleting" className="size-3.5 shrink-0 text-zinc-400" /> : <WorkspaceStatusIcon status={workspace.status} />}
            </button>
          )}
          <p className="mt-1 truncate font-mono text-xs text-zinc-500 dark:text-zinc-400">{workspace.model ?? workspace.modelProvider}</p>
        </div>
        <WorkspaceActions workspace={workspace} deleting={deleting} className="relative z-20 -mt-1 -mr-1"
          onRename={() => setEditing(true)} {...handlers} />
      </div>
      <div className="flex items-center gap-2 px-4">
        <span className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${status.tone}`}>
          <span aria-hidden className={`size-1.5 rounded-full ${status.dot}`} />{status.label}
        </span>
        {shared && (
          <span title="Shared with you by its owner" className="inline-flex items-center gap-1 rounded-full bg-sky-100 px-2 py-0.5 text-xs font-medium text-sky-700 ring-1 ring-sky-600/15 ring-inset dark:bg-sky-900/40 dark:text-sky-300 dark:ring-sky-400/20">
            <Users aria-hidden className="size-3" />Shared
          </span>
        )}
        {workspace.restartRequired && !deleting && (
          <span className="text-xs text-amber-700 dark:text-amber-400">Restart to apply changes</span>
        )}
      </div>
      <div className={`pointer-events-none relative z-10 mt-4 flex items-center gap-1 border-t [&>button]:pointer-events-auto px-2 py-2 ${shared ? "border-sky-100 dark:border-sky-900/50" : "border-zinc-100 dark:border-zinc-800"}`}>
        {quick.map(({ label, icon: Icon, run, disabled }) => (
          <button key={label} type="button" aria-label={`${label} of ${workspace.name}`} title={label} disabled={disabled || deleting} onClick={run}
            className="inline-flex size-8 items-center justify-center rounded-lg text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-900 disabled:pointer-events-none disabled:opacity-40 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white">
            <Icon aria-hidden className="size-4" />
          </button>
        ))}
        {/* A cue only: clicks fall through to the card's own link underneath. */}
        <span aria-hidden className="pointer-events-none ml-auto inline-flex items-center gap-1 px-2 py-1.5 text-xs font-medium text-zinc-400 transition-colors group-hover:text-zinc-900 dark:group-hover:text-white">
          Open<ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
        </span>
      </div>
    </li>
  );
}

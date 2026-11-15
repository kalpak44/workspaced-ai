import { Layers, MessageSquare, Plus, SearchX, Store } from "lucide-react";
import { useState } from "react";
import { navigate } from "../../hooks/useRoute";
import { DEFAULT_NAME, type Workspace } from "../../hooks/useWorkspaces";
import { Skeleton } from "../atoms";
import { EmptyState, PageHeader, SearchField, WorkspaceCard } from "../molecules";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onNew: () => void;
  readonly onRename: (id: string, name: string) => void;
  readonly onRestart: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

// What a first-time user is about to do, so the empty page explains the product.
const ONBOARDING = [
  { icon: Layers, title: "Create a workspace", text: "Its own machine, files and AI agent, isolated from your other work." },
  { icon: MessageSquare, title: "Describe a task", text: "The agent writes code, runs commands and reports back as it goes." },
  { icon: Store, title: "Reuse recipes", text: "Apply a recipe from the store to set up a whole stack in one go." },
] as const;

// Searching a handful of cards is slower than reading them, so the box appears only past this.
const SEARCH_FROM = 7;

/** Home: every workspace as a card with its state and a way straight into each of its tools. */
export function WorkspacesOverview({ workspaces, loading, deleting, onNew, onRename, onRestart, onDelete }: Props) {
  const [query, setQuery] = useState("");
  const needle = query.trim().toLowerCase();
  const shown = workspaces.filter((workspace) => workspace.name.toLowerCase().includes(needle));
  const running = workspaces.filter((workspace) => workspace.status === "READY").length;

  if (!loading && workspaces.length === 0) {
    return (
      <>
        <PageHeader title="Workspaces" />
        <div className="flex flex-1 flex-col items-center justify-center px-4 py-10">
          <EmptyState icon={Layers} title="No workspaces" hint="A workspace is a sandbox with its own agent. Create one to get started."
            action={{ label: DEFAULT_NAME, onClick: onNew }} />
          <ol aria-label="How it works" className="mt-2 grid w-full max-w-3xl gap-3 sm:grid-cols-3">
            {ONBOARDING.map(({ icon: Icon, title, text }, index) => (
              <li key={title} className="card flex flex-col gap-2 p-4">
                <span className="flex items-center gap-2 text-xs font-medium text-zinc-500 dark:text-zinc-400">
                  <span className="flex size-6 items-center justify-center rounded-full bg-zinc-100 text-[11px] tabular-nums dark:bg-zinc-800">{index + 1}</span>
                  <Icon aria-hidden className="size-3.5" />
                </span>
                <span className="text-sm font-semibold">{title}</span>
                <span className="text-xs text-zinc-500 dark:text-zinc-400">{text}</span>
              </li>
            ))}
          </ol>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title="Workspaces"
        description={loading ? "Loading workspaces…" : `${workspaces.length} ${workspaces.length === 1 ? "workspace" : "workspaces"} · ${running} running`}
        actions={workspaces.length >= SEARCH_FROM && (
          <SearchField label="Search workspaces" placeholder="Search workspaces…" value={query} onChange={setQuery} className="w-full sm:w-64" />
        )} />
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-4 pt-6 pb-10 sm:px-6">

        {loading ? (
          <ul aria-hidden aria-busy="true" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2].map((index) => (
              <li key={index} className="card flex flex-col gap-3 p-4">
                <span className="flex items-center gap-3"><Skeleton className="size-10 rounded-xl" /><span className="flex flex-1 flex-col gap-2"><Skeleton className="h-3.5 w-1/2" /><Skeleton className="h-2.5 w-1/3" /></span></span>
                <Skeleton className="h-5 w-20 rounded-full" />
                <Skeleton className="mt-3 h-8 w-full" />
              </li>
            ))}
          </ul>
        ) : shown.length === 0 ? (
          <div className="card flex"><EmptyState icon={SearchX} title="Nothing matches" hint={`No workspace is called “${query.trim()}”.`} secondaryAction={{ label: "Clear search", onClick: () => setQuery("") }} /></div>
        ) : (
          <ul aria-label="Workspaces" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {shown.map((workspace) => (
              <WorkspaceCard key={workspace.id} workspace={workspace} deleting={deleting.has(workspace.id)}
                onOpen={() => navigate({ name: "workspace", workspaceId: workspace.id })}
                onRename={(name) => onRename(workspace.id, name)}
                onSettings={() => navigate({ name: "settings", workspaceId: workspace.id })}
                onTerminal={() => navigate({ name: "terminal", workspaceId: workspace.id })}
                onMonitoring={() => navigate({ name: "monitoring", workspaceId: workspace.id })}
                onRestart={() => onRestart(workspace.id)}
                onDelete={() => onDelete(workspace.id)} />
            ))}
            {needle === "" && (
              <li className="flex">
                <button type="button" onClick={onNew}
                  className="flex min-h-44 w-full flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-zinc-300 text-sm font-medium text-zinc-500 transition-colors hover:border-zinc-400 hover:bg-white hover:text-zinc-900 dark:border-zinc-700 dark:text-zinc-400 dark:hover:border-zinc-600 dark:hover:bg-zinc-900 dark:hover:text-white">
                  <span className="flex size-10 items-center justify-center rounded-full bg-zinc-100 dark:bg-zinc-800"><Plus aria-hidden className="size-5" /></span>
                  Create a workspace
                </button>
              </li>
            )}
          </ul>
        )}
      </div>
    </>
  );
}

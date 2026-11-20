import type { Workspace } from "../../hooks/useWorkspaces";
import { CirclePause, Server, ServerCrash, Sparkles, Wallet } from "lucide-react";
import { navigate } from "../../hooks/useRoute";
import { EmptyState } from "../molecules";

/**
 * What a workspace page shows instead of its content while there is no harness to talk to;
 * null once it is ready. Deleting comes first: the VM is going on purpose, so "unavailable"
 * would mislead.
 */
export function WorkspaceStatusView({
  workspace,
  deleting,
}: {
  readonly workspace: Workspace;
  readonly deleting: boolean;
}) {
  if (deleting) {
    return <EmptyState busy title="Deleting workspace" hint="Stopping its environment…" />;
  }
  if (workspace.status === "INITIALIZING") {
    return (
      <section role="status" aria-label="Workspace starting" className="flex min-h-0 flex-1 flex-col items-center justify-center gap-5 overflow-y-auto px-6 py-8 text-center">
        <div aria-hidden className="relative flex size-24 shrink-0 items-center justify-center">
          <span className="absolute inset-1 rounded-full border border-amber-200 motion-safe:animate-pulse dark:border-amber-900" />
          <span className="absolute inset-3 rounded-full bg-amber-50 dark:bg-amber-950/40" />
          <div className="relative flex size-14 items-center justify-center rounded-xl border border-zinc-200 bg-white shadow-sm dark:border-zinc-700 dark:bg-zinc-900">
            <Server className="size-7 text-zinc-700 dark:text-zinc-300" strokeWidth={1.5} />
          </div>
          <span className="absolute right-0 bottom-2 flex size-8 items-center justify-center rounded-full border border-amber-200 bg-amber-50 text-amber-600 motion-safe:animate-pulse dark:border-amber-800 dark:bg-amber-950 dark:text-amber-400"><Sparkles className="size-4" /></span>
        </div>
        <div className="max-w-sm space-y-2">
          <h3 className="text-lg font-semibold tracking-tight">Starting the workspace</h3>
          <p className="text-sm text-pretty text-zinc-500 dark:text-zinc-400">Your cloud computer and assistant are getting ready. This usually takes 30–60 seconds, sometimes a little longer.</p>
        </div>
        <div aria-hidden className="h-1.5 w-40 shrink-0 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
          <div className="h-full w-1/3 rounded-full bg-amber-400 motion-safe:animate-workspace-boot dark:bg-amber-500" />
        </div>
        <p className="max-w-xs text-xs text-zinc-500 dark:text-zinc-400">You can relax. This page updates automatically when everything is ready.</p>
      </section>
    );
  }
  if (workspace.status === "PAUSED") {
    return (
      <EmptyState
        icon={CirclePause}
        title="Workspace paused"
        hint="Your balance ran out, so its VM is shut down with its files kept. Add funds and it starts again within a few minutes; otherwise it is deleted on the date the billing page shows."
        action={{ label: "Add funds", icon: Wallet, onClick: () => navigate({ name: "billing" }) }}
      />
    );
  }
  if (workspace.status === "FAILED") {
    return (
      <EmptyState
        icon={ServerCrash}
        title="Workspace unavailable"
        hint="Its environment stopped or could not start. Delete it and create a new one."
      />
    );
  }
  return null;
}


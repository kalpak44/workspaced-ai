import { describeRestore, type RestoreProgress, useRestoreProgress } from "../../hooks/useRestoreProgress";
import type { Workspace } from "../../hooks/useWorkspaces";
import { ProgressBar } from "../atoms";
import { EmptyState } from "../molecules";

/**
 * What a workspace page shows instead of its content while there is no harness to talk to;
 * null once it is ready. Deleting comes first: the pod is going on purpose, so "unavailable"
 * would mislead.
 */
export function WorkspaceStatusView({
  workspace,
  deleting,
}: {
  readonly workspace: Workspace;
  readonly deleting: boolean;
}) {
  const restore = useRestoreProgress(workspace.id, !deleting && workspace.status === "INITIALIZING");
  if (deleting) {
    return <EmptyState busy title="Deleting workspace" hint="Stopping its environment…" />;
  }
  if (workspace.status === "INITIALIZING" && restore) {
    return <RestoreView restore={restore} />;
  }
  if (workspace.status === "INITIALIZING") {
    return (
      <EmptyState
        busy
        title="Starting the workspace"
        hint="Its agent is getting ready. This page opens on its own when it is."
      />
    );
  }
  if (workspace.status === "FAILED") {
    return (
      <EmptyState
        title="Workspace unavailable"
        hint="Its environment stopped or could not start. Delete it and create a new one."
      />
    );
  }
  return null;
}

/** A restore can take minutes on a large home, so it shows how far it is rather than a spinner. */
function RestoreView({ restore }: { readonly restore: RestoreProgress }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 px-6 text-center">
      <h2 className="text-lg font-semibold">Restoring the backup</h2>
      <div className="w-full max-w-md space-y-2">
        <ProgressBar label="Restore progress" value={restore.progress} />
        <div className="flex justify-between gap-3 text-sm text-zinc-500 dark:text-zinc-400">
          <span className="min-w-0 truncate">{describeRestore(restore)}</span>
          <span className="tabular-nums">{restore.progress}%</span>
        </div>
      </div>
      <p className="max-w-sm text-xs text-zinc-500 dark:text-zinc-400">The workspace opens on its own when it is done. Its old files stay until the archive has been checked.</p>
    </div>
  );
}

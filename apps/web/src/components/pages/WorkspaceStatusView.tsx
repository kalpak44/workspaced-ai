import type { Workspace } from "../../hooks/useWorkspaces";
import { CirclePause, ServerCrash, Wallet } from "lucide-react";
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
      <EmptyState
        busy
        title="Starting the workspace"
        hint="Its agent is getting ready. This page opens on its own when it is."
      />
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


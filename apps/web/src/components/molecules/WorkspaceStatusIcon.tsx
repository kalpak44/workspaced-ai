import { CircleAlert } from "lucide-react";
import type { WorkspaceStatus } from "../../hooks/useWorkspaces";
import { Spinner } from "../atoms";

/** Nothing for a ready workspace: only the states that need attention are marked. */
export function WorkspaceStatusIcon({ status }: { readonly status: WorkspaceStatus }) {
  if (status === "INITIALIZING") {
    return <Spinner label="Initializing" className="size-3.5 shrink-0 text-zinc-400" />;
  }
  if (status === "FAILED") {
    return (
      <CircleAlert role="status" aria-label="Failed" className="size-3.5 shrink-0 text-rose-500" />
    );
  }
  return null;
}

import { queuePrompt } from "../../hooks/useChat";
import { navigate } from "../../hooks/useRoute";
import { useSessions } from "../../hooks/useSessions";
import type { Workspace } from "../../hooks/useWorkspaces";
import { SessionList } from "../organisms";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

interface Props {
  readonly workspace: Workspace;
  readonly deleting: boolean;
}

/** A workspace's sessions, and the box that starts a new one. */
export function WorkspacePage({ workspace, deleting }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const sessions = useSessions(ready ? workspace.id : null);
  const open = (sessionId: string) =>
    navigate({ name: "session", workspaceId: workspace.id, sessionId });

  /** The session page sends the text, once it is listening for the reply. */
  const start = async (text: string) => {
    const session = await sessions.create();
    if (!session) {
      return false;
    }
    queuePrompt(session.id, text);
    open(session.id);
    return true;
  };

  return (
    <>
      <h1 className="sr-only">{workspace.name}</h1>
      {ready ? (
        <SessionList
          sessions={sessions.sessions}
          loading={sessions.loading}
          deleting={sessions.deleting}
          error={sessions.error}
          onDismissError={sessions.dismissError}
          onStart={start}
          onOpen={open}
          onDelete={sessions.remove}
        />
      ) : (
        <WorkspaceStatusView workspace={workspace} deleting={deleting} />
      )}
    </>
  );
}

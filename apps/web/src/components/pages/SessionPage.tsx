import { Trash2 } from "lucide-react";
import { useEffect } from "react";
import { useChat } from "../../hooks/useChat";
import { navigate } from "../../hooks/useRoute";
import { useSessions } from "../../hooks/useSessions";
import type { Workspace } from "../../hooks/useWorkspaces";
import { IconButton } from "../atoms";
import { PageHeader } from "../molecules";
import { ChatModelPicker, ChatPanel } from "../organisms";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

interface Props {
  readonly workspace: Workspace;
  readonly sessionId: string;
  readonly onModelChanged: (model: string | null) => void;
  readonly deleting: boolean;
  /** Tells the shell the page's name, for the browser tab. */
  readonly onTitle: (title: string) => void;
}

/** One session's chat, full width. */
export function SessionPage({ workspace, sessionId, deleting, onTitle, onModelChanged }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const sessions = useSessions(ready ? workspace.id : null);
  const chat = useChat(ready ? workspace.id : null, ready ? sessionId : null, sessions.retitle);
  const session = sessions.sessions.find((s) => s.id === sessionId);
  const title = session?.title ?? "New session";
  const back = () => navigate({ name: "workspace", workspaceId: workspace.id });

  useEffect(() => onTitle(title), [onTitle, title]);

  // A session that is not in the list was deleted, or the link was wrong.
  const missing = ready && !sessions.loading && !sessions.error && !session;
  useEffect(() => {
    if (missing) {
      navigate({ name: "workspace", workspaceId: workspace.id }, { replace: true });
    }
  }, [missing, workspace.id]);

  return (
    <>
      <PageHeader
        title={title}
        crumbs={[{ label: workspace.name, onClick: back }]}
        actions={
          ready && (
            <IconButton
              label="Delete session"
              icon={Trash2}
              onClick={() => {
                sessions.remove(sessionId);
                back();
              }}
            />
          )
        }
      />
      {ready ? (
        <ChatPanel
          messages={chat.messages}
          loading={chat.loading && chat.messages.length === 0}
          busy={chat.busy}
          error={sessions.error ?? chat.error}
          onDismissError={() => {
            sessions.dismissError();
            chat.dismissError();
          }}
          modelControl={<ChatModelPicker workspace={workspace} disabled={chat.busy} onChanged={onModelChanged} />}
          onEdit={chat.edit}
          onSend={chat.send}
          onStop={chat.stop}
        />
      ) : (
        <WorkspaceStatusView workspace={workspace} deleting={deleting} />
      )}
    </>
  );
}

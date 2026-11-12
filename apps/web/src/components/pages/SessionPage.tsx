import { useEffect, useState } from "react";
import { useChat } from "../../hooks/useChat";
import { navigate } from "../../hooks/useRoute";
import { useSessions } from "../../hooks/useSessions";
import { useSessionAgents } from "../../hooks/useSessionAgents";
import { FileLinkContext, useSessionFiles } from "../../hooks/useSessionFiles";
import type { Workspace } from "../../hooks/useWorkspaces";
import { PageHeader } from "../molecules";
import { ChatModelPicker, ChatPanel } from "../organisms";
import { ChatAgentPicker } from "../organisms/ChatAgentPicker";
import { DeleteSessionDialog } from "../organisms/DeleteSessionDialog";
import { SessionSidebar } from "../organisms/SessionSidebar";
import { ContextLayout } from "../templates/ContextLayout";
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
  const agents = useSessionAgents(ready ? workspace.id : null, ready ? sessionId : null);
  const files = useSessionFiles(ready ? workspace.id : null, ready ? sessionId : null);
  const switchingAgent = agents.saving !== null;
  const session = sessions.sessions.find((s) => s.id === sessionId);
  const title = session?.title ?? "New session";
  const back = () => navigate({ name: "workspace", workspaceId: workspace.id });
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const deleteTarget = sessions.sessions.find((item) => item.id === deleteId);

  useEffect(() => onTitle(title), [onTitle, title]);

  // A session that is not in the list was deleted, or the link was wrong.
  const missing = ready && !sessions.loading && !sessions.error && !session;
  useEffect(() => {
    if (missing) {
      navigate({ name: "workspace", workspaceId: workspace.id }, { replace: true });
    }
  }, [missing, workspace.id]);

  return (
    <ContextLayout compactSidebar sidebar={<SessionSidebar workspaceName={workspace.name} sessions={sessions.sessions} selectedId={sessionId} loading={sessions.loading} deleting={sessions.deleting} onBack={back} onSettings={() => navigate({ name: "settings", workspaceId: workspace.id })} onDelete={setDeleteId} onOpen={(id) => navigate({ name: "session", workspaceId: workspace.id, sessionId: id })} />}>
      <PageHeader
        title={title}
        crumbs={[{ label: workspace.name, onClick: back }]}
      />
      {ready ? (
        <FileLinkContext.Provider value={files.open}>
        <ChatPanel
          messages={chat.messages}
          loading={chat.loading && chat.messages.length === 0}
          busy={chat.busy}
          disabled={switchingAgent}
          forms={chat.forms}
          onAnswer={chat.answerForm}
          error={sessions.error ?? chat.error ?? files.linkError}
          onDismissError={() => {
            sessions.dismissError();
            chat.dismissError();
            files.dismissLinkError();
          }}
          modelControl={<><ChatAgentPicker agents={agents} disabled={chat.busy || chat.forms.length > 0} /><ChatModelPicker workspace={workspace} disabled={chat.busy || switchingAgent} onChanged={onModelChanged} /></>}
          onEdit={chat.edit}
          onSend={async (text) => {
            const sent = await chat.send(files.withAttachments(text));
            if (sent) files.clear();
            return sent;
          }}
          onStop={chat.stop}
          attachments={files.attachments}
          onAttach={files.attach}
          onDetach={files.detach}
        />
        </FileLinkContext.Provider>
      ) : (
        <WorkspaceStatusView workspace={workspace} deleting={deleting} />
      )}
      {deleteTarget && <DeleteSessionDialog title={deleteTarget.title ?? "New session"} onClose={() => setDeleteId(null)} onConfirm={() => { void sessions.remove(deleteTarget.id); setDeleteId(null); }} />}
    </ContextLayout>
  );
}

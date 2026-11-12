import { queuePrompt } from "../../hooks/useChat";
import { navigate } from "../../hooks/useRoute";
import { useSessions } from "../../hooks/useSessions";
import { attachmentNote, useFileQueue } from "../../hooks/useSessionFiles";
import { useState } from "react";
import type { Workspace } from "../../hooks/useWorkspaces";
import { ChatModelPicker, SessionList } from "../organisms";
import { DeleteSessionDialog } from "../organisms/DeleteSessionDialog";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

interface Props {
  readonly workspace: Workspace;
  readonly deleting: boolean;
  readonly onModelChanged: (model: string | null) => void;
}

/** A workspace's sessions, and the box that starts a new one. */
export function WorkspacePage({ workspace, deleting, onModelChanged }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const sessions = useSessions(ready ? workspace.id : null);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [deleteMany, setDeleteMany] = useState<readonly string[] | null>(null);
  const deleteTarget = sessions.sessions.find((session) => session.id === deleteId);
  const files = useFileQueue();
  const open = (sessionId: string) =>
    navigate({ name: "session", workspaceId: workspace.id, sessionId });

  /**
   * The session page sends the text, once it is listening for the reply. Files go into the new
   * session first; if one fails, the session is removed again and the text and files stay here.
   */
  const start = async (text: string) => {
    const session = await sessions.create();
    if (!session) {
      return false;
    }
    const paths = await files.uploadTo(workspace.id, session.id);
    if (paths === null) {
      void sessions.remove(session.id);
      return false;
    }
    files.clear();
    queuePrompt(session.id, attachmentNote(text, paths));
    open(session.id);
    return true;
  };

  return (
    <>
      <h1 className="sr-only">{workspace.name}</h1>
      {ready ? (
        <SessionList
          modelControl={<ChatModelPicker workspace={workspace} onChanged={onModelChanged} />}
          sessions={sessions.sessions}
          loading={sessions.loading}
          deleting={sessions.deleting}
          error={sessions.error}
          onDismissError={sessions.dismissError}
          onStart={start}
          onOpen={open}
          onDelete={setDeleteId}
          onDeleteMany={setDeleteMany}
          attachments={files.attachments}
          onAttach={files.attach}
          onDetach={files.detach}
        />
      ) : (
        <WorkspaceStatusView workspace={workspace} deleting={deleting} />
      )}
      {deleteMany && <DeleteSessionDialog count={deleteMany.length} onClose={() => setDeleteMany(null)} onConfirm={() => { void sessions.removeMany(deleteMany); setDeleteMany(null); }} />}
      {deleteTarget && <DeleteSessionDialog title={deleteTarget.title ?? "New session"} onClose={() => setDeleteId(null)} onConfirm={() => { void sessions.remove(deleteTarget.id); setDeleteId(null); }} />}
    </>
  );
}

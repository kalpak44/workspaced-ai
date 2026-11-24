import { useEffect, useState } from "react";
import { useChat } from "../../hooks/useChat";
import { navigate } from "../../hooks/useRoute";
import { useSessions } from "../../hooks/useSessions";
import { useSessionAgents } from "../../hooks/useSessionAgents";
import { FileLinkContext, useSessionFiles } from "../../hooks/useSessionFiles";
import type { Workspace } from "../../hooks/useWorkspaces";
import { reportChatBusy, reportChatCheckpoint, reportChatPageLink, reportChatPublicQuestion, reportTourChatIssue, reportTourAction, TOUR_DESIGN_IDEAS, TOUR_DESIGN_PROMPT, TOUR_PUBLIC_PROMPT, TOUR_STEP, useTourProgress } from "../../lib/tour";
import { publicTourAnswer, recoverTourChat } from "../../hooks/useDemoTour";
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
  const tour = useTourProgress();
  const tourCanSend = !tour || (tour.workspaceId === workspace.id && tour.sessionId === sessionId
    && (tour.step === TOUR_STEP.CONFIRM_PUBLIC || tour.step === TOUR_STEP.DESIGN));
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
  // The guided tour waits on the agent finishing; this page is where that is known.
  useEffect(() => {
    // Idle before history arrives, or after an error, is not a completed deployment.
    const lastUser = chat.messages.findLastIndex((message) => message.role === "user");
    const reply = chat.messages.slice(lastUser + 1).filter((message) => message.role === "assistant")
      .flatMap((message) => message.parts.filter((part) => part.type === "text").map((part) => part.text ?? "")).join("\n");
    const question = tour && (tour.step === TOUR_STEP.BUILD || tour.step === TOUR_STEP.CONFIRM_PUBLIC) && publicTourAnswer(chat.forms);
    reportChatPublicQuestion(sessionId, !!question);
    const waiting = !ready || chat.loading || (chat.busy && !question) || !!chat.error || !reply.trim();
    reportChatBusy(sessionId, waiting);
    reportChatCheckpoint(sessionId, waiting ? "" : reply);
    const replies = chat.messages.filter(message => message.role === "assistant");
    if (tour?.step === TOUR_STEP.DEPLOY) {
      // A pre-publication question may mention the address before any server is running.
      if (!waiting) reportChatPageLink(sessionId, replies.at(-1)?.parts.filter(part => part.type === "text").map(part => part.text ?? "").join("\n") ?? "");
    } else if (!chat.loading && (!tour || tour.step > TOUR_STEP.DEPLOY)) {
      reportChatPageLink(sessionId, replies.toReversed().flatMap(message => message.parts.filter(part => part.type === "text").map(part => part.text ?? "")).join("\n"));
    }
  }, [sessionId, ready, chat.loading, chat.busy, chat.error, chat.messages, chat.forms, tour]);

  useEffect(() => {
    const issue = chat.error ?? (tour?.step === TOUR_STEP.BUILD && !chat.loading && !chat.busy && chat.messages.length === 0
      ? "Your recipe has not started yet. Click Retry to send it to your assistant." : null);
    if (!tour || !issue) { reportTourChatIssue(sessionId, null); return; }
    reportTourChatIssue(sessionId, { message: issue, retry: async () => {
      await recoverTourChat(workspace.id, sessionId, tour.step === TOUR_STEP.BUILD, chat.send, chat.reconnect);
    } });
    return () => reportTourChatIssue(sessionId, null);
  }, [sessionId, workspace.id, tour?.step, chat.error, chat.loading, chat.busy, chat.messages.length]);

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
        variant="bar"
        title={title}
        crumbs={[{ label: workspace.name, onClick: back }]}
      />
      {ready ? (
        <FileLinkContext.Provider value={files.open}>
        <ChatPanel
          messages={chat.messages}
          loading={chat.loading && chat.messages.length === 0}
          busy={chat.busy}
          disabled={switchingAgent || !tourCanSend}
          forms={chat.forms}
          onAnswer={async (id, answer) => {
            const expected = publicTourAnswer(chat.forms);
            const accepted = await chat.answerForm(id, answer);
            if (accepted && tour?.step === TOUR_STEP.CONFIRM_PUBLIC && expected?.id === id
                && JSON.stringify(answer) === JSON.stringify(expected.answer)) {
              reportTourAction({ type: "public-confirmed", workspaceId: workspace.id, sessionId });
            }
            return accepted;
          }}
          error={sessions.error ?? chat.error ?? files.linkError}
          onDismissError={() => {
            sessions.dismissError();
            chat.dismissError();
            files.dismissLinkError();
          }}
          modelControl={<><ChatAgentPicker agents={agents} disabled={chat.busy || chat.forms.length > 0} /><ChatModelPicker workspace={workspace} disabled={chat.busy || switchingAgent} onChanged={onModelChanged} /></>}
          onEdit={chat.edit}
          onSend={async (text) => {
            if (!tourCanSend) return false;
            if (tour && (tour.step === TOUR_STEP.CONFIRM_PUBLIC ? text !== TOUR_PUBLIC_PROMPT
              : text !== TOUR_DESIGN_PROMPT && !TOUR_DESIGN_IDEAS.includes(text))) return false;
            const sent = await chat.send(files.withAttachments(text));
            if (sent) { files.clear(); reportTourAction({ type: "message-sent", workspaceId: workspace.id, sessionId }); }
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

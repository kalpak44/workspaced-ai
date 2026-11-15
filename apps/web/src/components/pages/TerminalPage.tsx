import { RefreshCw, SquareTerminal, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { navigate } from "../../hooks/useRoute";
import { useTerminals, type TerminalSession } from "../../hooks/useTerminals";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button, IconButton } from "../atoms";
import { EmptyState, ErrorBanner, Modal, ModalBody, ModalFooter } from "../molecules";
import { TerminalConsole } from "../organisms/TerminalConsole";
import { TerminalSidebar } from "../organisms/TerminalSidebar";
import { ContextLayout } from "../templates/ContextLayout";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

interface Props {
  readonly workspace: Workspace;
  readonly terminalId?: string;
  readonly deleting: boolean;
}

export function TerminalPage({ workspace, terminalId, deleting }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const terminals = useTerminals(ready ? workspace.id : null);
  const [deleteTarget, setDeleteTarget] = useState<TerminalSession | null>(null);
  const selected = terminals.terminals.find((terminal) => terminal.id === terminalId);
  const open = (id?: string, replace = false) => navigate({ name: "terminal", workspaceId: workspace.id, ...(id ? { terminalId: id } : {}) }, { replace });

  useEffect(() => {
    if (ready && !terminals.loading && !terminals.error && !selected && terminals.terminals.length > 0) {
      navigate({ name: "terminal", workspaceId: workspace.id, terminalId: terminals.terminals[0].id }, { replace: true });
    } else if (ready && !terminals.loading && !terminals.error && terminalId && terminals.terminals.length === 0) {
      navigate({ name: "terminal", workspaceId: workspace.id }, { replace: true });
    }
  }, [ready, terminals.loading, terminals.error, terminals.terminals, selected, terminalId, workspace.id]);

  async function create() {
    const terminal = await terminals.create();
    if (terminal) open(terminal.id);
  }

  return <ContextLayout contained sidebar={<TerminalSidebar terminals={terminals.terminals} selectedId={selected?.id} loading={terminals.loading} creating={terminals.creating} removing={terminals.removing} disabled={!ready || terminals.loading || terminals.removing !== null} onCreate={() => { void create(); }} onSelect={(id) => { setDeleteTarget(null); open(id); }} onDelete={(id) => setDeleteTarget(terminals.terminals.find((terminal) => terminal.id === id) ?? null)} />}>
    <div className="mb-3 flex min-w-0 items-center justify-between gap-3">
      <h1 className="truncate text-lg font-semibold tracking-tight">{selected?.title ?? "Terminals"}</h1>
      <IconButton label="Refresh terminals" title="Refresh terminals" icon={RefreshCw} disabled={!ready || terminals.creating || terminals.removing !== null} onClick={() => { terminals.dismissError(); void terminals.refresh(); }} />
    </div>
    {terminals.error && <ErrorBanner message={terminals.error} onDismiss={terminals.dismissError} />}
    {!ready ? <WorkspaceStatusView workspace={workspace} deleting={deleting} /> : terminals.loading ? <EmptyState busy title="Loading terminals…" hint="Fetching the workspace's shell sessions" /> : selected ? <div className="flex min-h-[60dvh] flex-1 flex-col gap-3">
      <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-zinc-500 dark:text-zinc-400"><span className="font-mono [overflow-wrap:anywhere]">{selected.cwd}</span><span>{selected.command} · PID {selected.pid}</span>{selected.status === "exited" && <span>Exit code {selected.exitCode ?? "unknown"}</span>}</p>
      <TerminalConsole key={selected.id} workspaceId={workspace.id} terminalId={selected.id} running={selected.status === "running"} onDisconnect={terminals.refresh} />
    </div> : <div className="card flex flex-1"><EmptyState icon={SquareTerminal} title="No terminals yet" hint="Create a shell to work with your workspace's files. You can keep several terminals running and switch between them." action={{ label: "Create terminal", onClick: () => { void create(); } }} /></div>}
    {deleteTarget && <Modal title="Delete terminal?" description="This stops the shell and any commands running in it." onClose={() => { if (!terminals.removing) setDeleteTarget(null); }}>
      <ModalBody><p className="text-sm text-zinc-600 dark:text-zinc-300">Delete <strong>{deleteTarget.title}</strong>? Workspace files and AI conversations will be kept.</p>{terminals.error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{terminals.error}</p>}</ModalBody>
      <ModalFooter><Button label="Cancel" variant="secondary" autoFocus disabled={terminals.removing !== null} onClick={() => setDeleteTarget(null)} /><Button label="Delete terminal" variant="danger" icon={Trash2} busy={terminals.removing !== null} onClick={() => { void terminals.remove(deleteTarget.id).then((removed) => { if (removed) { setDeleteTarget(null); if (selected?.id === deleteTarget.id) open(undefined, true); } }); }} /></ModalFooter>
    </Modal>}
  </ContextLayout>;
}

import { ArrowLeft, Archive } from "lucide-react";
import { useState } from "react";
import { type Backup, useBackups } from "../../hooks/useBackups";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button } from "../atoms";
import { EmptyState, ErrorBanner, Modal, ModalBody, ModalFooter, PageHeader } from "../molecules";
import { BackupList, CreateFromBackupDialog, RestoreBackupDialog } from "../organisms";
import { ContextLayout } from "../templates/ContextLayout";

interface Props {
  /** The workspace whose backups are open; the newest group's when unset or unknown. */
  readonly group?: string;
  /** Told about a restore so the sidebar shows the workspace restarting. */
  readonly onRestored: (workspaceId: string) => void;
  readonly onCreated: (workspace: Workspace) => void;
}

/**
 * Laid out like the settings page: the workspaces that have backups on the left, deleted ones
 * included, and the selected one's versions in the main column.
 */
export function BackupsPage({ group, onRestored, onCreated }: Props) {
  const { groups, loading, error, dismissError, restore, remove, download, createWorkspace } = useBackups();
  const [restoring, setRestoring] = useState<Backup | null>(null);
  const [creating, setCreating] = useState<Backup | null>(null);
  const [deleting, setDeleting] = useState<Backup | null>(null);
  const selected = groups.find((g) => g.workspaceId === group) ?? groups.at(0) ?? null;

  let content;
  if (loading) {
    content = <EmptyState busy title="Loading backups…" hint="Fetching them from the API" />;
  } else if (!selected) {
    content = <EmptyState title="No backups yet" hint="Back up a workspace from its menu in the workspace list." />;
  } else {
    content = (
      <div className="w-full px-4 py-6 sm:px-6 sm:py-8">
        <p className="mb-6 text-xs text-zinc-500 dark:text-zinc-400">
          {selected.exists
            ? "Restore puts a version back into this workspace; Create new starts a separate one from it."
            : "This workspace was deleted. Create a new one from any of its versions."}
        </p>
        <BackupList backups={selected.backups} canRestore={selected.exists}
          onRestore={setRestoring} onCreate={setCreating} onDelete={setDeleting}
          onDownload={(backup) => void download(backup.id).then((url) => { if (url) globalThis.open(url, "_blank", "noopener"); })} />
      </div>
    );
  }

  return (
    <ContextLayout sidebar={<>
      <div className="flex h-14 shrink-0 items-center border-b border-zinc-200 px-4 dark:border-zinc-800">
        <button type="button" onClick={() => navigate({ name: "general-settings" })} className="flex items-center gap-2 rounded-md text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white">
          <ArrowLeft aria-hidden className="size-4" />Settings
        </button>
      </div>
      <nav aria-label="Backed-up workspaces" className="flex gap-1 overflow-x-auto p-3 sm:flex-col">
        {groups.map((g) => (
          <button key={g.workspaceId} type="button" aria-current={g === selected ? "page" : undefined}
            onClick={() => navigate({ name: "backups", group: g.workspaceId }, { replace: true })}
            className={`flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium ${g === selected ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}>
            <Archive aria-hidden className="size-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{g.name}</span>
            {!g.exists && <span className="text-xs font-normal text-zinc-400">deleted</span>}
          </button>
        ))}
      </nav>
    </>}>
      <PageHeader title={selected ? `Backups · ${selected.name}` : "Backups"} />
      {error && <ErrorBanner message={error} onDismiss={dismissError} />}
      {content}
      {restoring && selected && (
        <RestoreBackupDialog name={selected.name} createdAt={restoring.createdAt} onClose={() => setRestoring(null)}
          onConfirm={() => restore(restoring.id).then((ok) => {
            if (ok) {
              onRestored(restoring.workspaceId);
              navigate({ name: "workspace", workspaceId: restoring.workspaceId });
            }
            return ok;
          })} />
      )}
      {creating && selected && (
        <CreateFromBackupDialog initialName={`${selected.name} copy`} canReuseKey={selected.exists} secrets={creating.secrets}
          onClose={() => setCreating(null)}
          onCreate={(name, apiKey) => createWorkspace(creating.id, { name, ...(apiKey ? { apiKey } : {}) }).then((result) => {
            if ("error" in result) return result.error;
            onCreated(result.workspace);
            navigate({ name: "workspace", workspaceId: result.workspace.id });
            return null;
          })} />
      )}
      {deleting && (
        <Modal title="Delete this backup?" description={new Date(deleting.createdAt).toLocaleString()} onClose={() => setDeleting(null)}>
          <ModalBody><p className="text-sm text-zinc-700 dark:text-zinc-300">The archive is removed from storage and cannot be restored afterwards.</p></ModalBody>
          <ModalFooter>
            <Button label="Cancel" variant="secondary" onClick={() => setDeleting(null)} autoFocus />
            <Button label="Delete backup" variant="danger" onClick={() => { void remove(deleting.id); setDeleting(null); }} />
          </ModalFooter>
        </Modal>
      )}
    </ContextLayout>
  );
}

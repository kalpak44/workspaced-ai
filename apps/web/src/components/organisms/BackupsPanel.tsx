import { Archive, Upload, X } from "lucide-react";
import { useEffect, useState } from "react";
import { type Backup, useBackups } from "../../hooks/useBackups";
import { describeRestore, useRestoreProgress } from "../../hooks/useRestoreProgress";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button, IconButton, ProgressBar, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";
import { BackupList } from "./BackupList";
import { ConfirmBackupDialog } from "./ConfirmBackupDialog";
import { CreateFromBackupDialog } from "./CreateFromBackupDialog";
import { DeleteBackupDialog } from "./DeleteBackupDialog";
import { RestoreBackupDialog } from "./RestoreBackupDialog";
import { UploadBackupDialog } from "./UploadBackupDialog";

interface Props {
  readonly workspace: Workspace;
  /** Told about a restore so the workspace shows as restarting at once. */
  readonly onRestored: (workspaceId: string) => void;
  readonly onCreated: (workspace: Workspace) => void;
}

/**
 * Back up, upload, restore, download and delete, all for this workspace. Backups of deleted
 * workspaces are listed under it too, since there is no other workspace left to show them.
 */
export function BackupsPanel({ workspace, onRestored, onCreated }: Props) {
  const {
    backups, deleted, loading, upload, error, dismissError,
    backUp, uploadFile, remove, restore, download, createWorkspace,
  } = useBackups(workspace.id);
  const [confirmingBackup, setConfirmingBackup] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [restoring, setRestoring] = useState<Backup | null>(null);
  const [creating, setCreating] = useState<Backup | null>(null);
  const [deleting, setDeleting] = useState<Backup | null>(null);
  // Set from the click until the workspace is up again, so the bar shows before the pod reports.
  const [restarting, setRestarting] = useState(false);
  const starting = workspace.status === "INITIALIZING";
  const restoreProgress = useRestoreProgress(workspace.id, starting);
  const busy = upload !== null || backups.some((b) => b.status === "RUNNING");

  useEffect(() => {
    if (workspace.status !== "INITIALIZING") setRestarting(false);
  }, [workspace.status]);

  const restoreNow = (id: string) =>
    restore(id).then((ok) => {
      if (ok) {
        setRestarting(true);
        onRestored(workspace.id);
      }
      return ok;
    });

  const startUpload = (file: File, thenRestore: boolean) =>
    void uploadFile(file).then((done) => {
      if (done && thenRestore) void restoreNow(done.id);
    });

  const openDownload = (backup: Backup) =>
    void download(backup.id).then((url) => {
      if (url) globalThis.open(url, "_blank", "noopener");
    });

  return (
    <div className="space-y-6">
      <SettingsSection title="Backups" description="Snapshots of this workspace's home, with its files, conversations and installed tools, and its settings. Restore one to put the workspace back as it was, or start a new workspace from it.">
        <div className="flex flex-wrap items-center gap-2">
          <Button label="Back up now" icon={Archive} disabled={workspace.status !== "READY" || busy} onClick={() => setConfirmingBackup(true)} />
          <Button label="Upload backup" icon={Upload} variant="secondary" disabled={busy} onClick={() => setUploading(true)} />
          {workspace.status !== "READY" && !starting && <span className="text-xs text-zinc-500 dark:text-zinc-400">Backing up needs the workspace running.</span>}
        </div>
        {starting && (restarting || restoreProgress) && (
          <div role="status" aria-label="Restore" className="space-y-1.5 rounded-xl border border-zinc-200 bg-zinc-50 p-4 dark:border-zinc-800 dark:bg-zinc-900/60">
            <p className="text-sm font-medium">Restoring the backup</p>
            <ProgressBar label="Restore progress" value={restoreProgress?.progress ?? 0} />
            <div className="flex justify-between gap-3 text-xs text-zinc-500 dark:text-zinc-400">
              <span className="min-w-0 truncate">{restoreProgress ? describeRestore(restoreProgress) : "Restarting the workspace…"}</span>
              <span className="tabular-nums">{restoreProgress?.progress ?? 0}%</span>
            </div>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">Its old files stay until the archive has been checked.</p>
          </div>
        )}
        {error && (
          <div role="alert" className="flex items-center justify-between gap-3 rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950 dark:text-rose-300">
            {error}
            <IconButton label="Dismiss" icon={X} onClick={dismissError} className="text-current" />
          </div>
        )}
        {loading
          ? <Spinner label="Loading backups" className="size-5 text-zinc-400" />
          : backups.length === 0
            ? <p className="text-sm text-zinc-500 dark:text-zinc-400">No backups yet. Back the workspace up, or upload an archive to restore.</p>
            : <BackupList backups={backups} canRestore={!starting && !busy} upload={upload}
                onRestore={setRestoring} onCreate={setCreating} onDownload={openDownload} onDelete={setDeleting} />}
      </SettingsSection>

      {deleted.length > 0 && (
        <SettingsSection title="Backups of deleted workspaces" description="Kept after their workspace was deleted. Start a new workspace from one, or download it and upload it above to restore it here.">
          {deleted.map((group) => (
            <div key={group.workspaceId} className="space-y-2">
              <h3 className="text-sm font-medium text-zinc-700 dark:text-zinc-300">{group.name}</h3>
              <BackupList backups={group.backups} canRestore={false}
                onCreate={setCreating} onDownload={openDownload} onDelete={setDeleting} />
            </div>
          ))}
        </SettingsSection>
      )}

      {confirmingBackup && <ConfirmBackupDialog name={workspace.name} onConfirm={backUp} onClose={() => setConfirmingBackup(false)} />}
      {uploading && <UploadBackupDialog name={workspace.name} canRestore={!starting} onUpload={startUpload} onClose={() => setUploading(false)} />}
      {restoring && (
        <RestoreBackupDialog name={workspace.name} createdAt={restoring.createdAt} onClose={() => setRestoring(null)}
          onConfirm={() => restoreNow(restoring.id)} />
      )}
      {creating && (
        <CreateFromBackupDialog initialName={`${creating.workspaceName} copy`} canReuseKey={creating.workspaceExists} secrets={creating.secrets}
          onClose={() => setCreating(null)}
          onCreate={(name, apiKey) => createWorkspace(creating.id, { name, ...(apiKey ? { apiKey } : {}) }).then((result) => {
            if ("error" in result) return result.error;
            onCreated(result.workspace);
            navigate({ name: "workspace", workspaceId: result.workspace.id });
            return null;
          })} />
      )}
      {deleting && (
        <DeleteBackupDialog createdAt={deleting.createdAt} uploading={deleting.status === "RUNNING"}
          onClose={() => setDeleting(null)} onConfirm={() => remove(deleting.id)} />
      )}
    </div>
  );
}

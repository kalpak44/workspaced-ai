import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  type Backup,
  completeUpload,
  createWorkspaceFromBackup,
  deleteBackup,
  downloadBackup,
  listBackups,
  restoreBackup,
  startBackup,
  startUpload,
  uploadPart,
  type WorkspaceFromBackup,
} from "../api/backupsApi";
import { ApiError } from "../api/http";
import type { Workspace } from "../api/workspacesApi";

export type { Backup, WorkspaceFromBackup } from "../api/backupsApi";

/** One deleted workspace's backups, newest first, under the name it had. */
export interface BackupGroup {
  readonly workspaceId: string;
  readonly name: string;
  readonly backups: readonly Backup[];
}

/** An archive this tab is sending; `sent` counts the bytes the API has taken. */
export interface Upload {
  readonly backupId: string | null;
  readonly fileName: string;
  readonly sent: number;
  readonly total: number;
  readonly finishing: boolean;
}

/** "created", or why not: a key the provider refused, or one the form has to ask for. */
export type FromBackupResult = { readonly workspace: Workspace } | { readonly error: string };

// A multiple of 5 MiB, the least S3 takes for every part but the last, and well under the
// 100 MB a request may carry through the proxies in front of the API.
export const PART_BYTES = 16 * 1024 * 1024;
const POLL_MS = 1000;

/**
 * One workspace's backups, and those of deleted workspaces, which nothing else would show. A
 * running backup is polled until it settles; the API keeps its progress, so leaving the page does
 * not stop it. An upload is sent by this tab, so leaving the page cancels it.
 */
export function useBackups(workspaceId: string) {
  const [backups, setBackups] = useState<Backup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [upload, setUpload] = useState<Upload | null>(null);
  const abort = useRef<AbortController | null>(null);

  const refresh = useCallback(
    () =>
      listBackups().then(
        (list) => setBackups(list),
        () => setError("Could not load backups"),
      ),
    [],
  );

  useEffect(() => {
    let active = true;
    listBackups()
      .then((list) => active && setBackups(list))
      .catch(() => active && setError("Could not load backups"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => () => abort.current?.abort(), []);

  const own = useMemo(() => backups.filter((b) => b.workspaceId === workspaceId), [backups, workspaceId]);
  const deleted = useMemo(() => groupBackups(backups.filter((b) => !b.workspaceExists)), [backups]);

  // An upload this tab is sending reports its own progress; any other running one is polled.
  const polling = own.some((b) => b.status === "RUNNING" && b.id !== upload?.backupId);
  useEffect(() => {
    if (!polling) return undefined;
    const timer = setInterval(() => void refresh(), POLL_MS);
    return () => clearInterval(timer);
  }, [polling, refresh]);

  const backUp = (): Promise<boolean> =>
    startBackup(workspaceId).then(
      (started) => {
        setBackups((current) => [started, ...current]);
        return true;
      },
      (failure: unknown) => {
        setError(startError(failure));
        return false;
      },
    );

  /** Sends the file in parts; resolves the completed backup, or null when it did not complete. */
  const uploadFile = async (file: File): Promise<Backup | null> => {
    const controller = new AbortController();
    abort.current = controller;
    setError(null);
    setUpload({ backupId: null, fileName: file.name, sent: 0, total: file.size, finishing: false });
    let id: string | null = null;
    try {
      const started = await startUpload(workspaceId, file.size);
      id = started.id;
      setBackups((current) => [started, ...current]);
      setUpload((u) => u && { ...u, backupId: started.id });
      for (let offset = 0, part = 1; offset < file.size; offset += PART_BYTES, part++) {
        const end = Math.min(offset + PART_BYTES, file.size);
        await uploadPart(started.id, part, file.slice(offset, end), controller.signal);
        setUpload((u) => u && { ...u, sent: end });
      }
      setUpload((u) => u && { ...u, finishing: true });
      const done = await completeUpload(started.id);
      setBackups((current) => current.map((b) => (b.id === done.id ? done : b)));
      if (done.status === "FAILED") {
        setError(done.error ?? "The upload did not complete.");
        return null;
      }
      return done;
    } catch (failure: unknown) {
      if (!controller.signal.aborted) setError(uploadError(failure));
      // Nothing else would ever end a half-sent upload, so it is cancelled on the API too.
      if (id) {
        const cancelled = id;
        await deleteBackup(cancelled).catch(() => {});
        setBackups((current) => current.filter((b) => b.id !== cancelled));
      }
      return null;
    } finally {
      if (abort.current === controller) abort.current = null;
      setUpload(null);
    }
  };

  const remove = (id: string): Promise<boolean> => {
    // Cancelling this tab's own upload stops its sending first, so no part follows the delete.
    if (upload?.backupId === id) abort.current?.abort();
    return deleteBackup(id).then(
      () => {
        setBackups((current) => current.filter((b) => b.id !== id));
        return true;
      },
      () => {
        setError("Could not delete the backup");
        return false;
      },
    );
  };

  const restore = (id: string): Promise<boolean> =>
    restoreBackup(id).then(
      () => true,
      () => {
        setError("Could not restore the backup");
        return false;
      },
    );

  const download = (id: string): Promise<string | null> =>
    downloadBackup(id).then(
      (link) => link.url,
      () => {
        setError("Could not prepare the download");
        return null;
      },
    );

  const createWorkspace = (id: string, request: WorkspaceFromBackup): Promise<FromBackupResult> =>
    createWorkspaceFromBackup(id, request).then(
      (workspace) => ({ workspace }),
      (failure: unknown) => ({ error: fromBackupError(failure) }),
    );

  return {
    backups: own,
    deleted,
    loading,
    upload,
    error,
    dismissError: () => setError(null),
    backUp,
    uploadFile,
    cancelUpload: () => abort.current?.abort(),
    remove,
    restore,
    download,
    createWorkspace,
  };
}

/** Newest backup's group first, so the workspace backed up last is at the top. */
export function groupBackups(backups: readonly Backup[]): BackupGroup[] {
  const groups = new Map<string, Backup[]>();
  for (const backup of backups) {
    groups.set(backup.workspaceId, [...(groups.get(backup.workspaceId) ?? []), backup]);
  }
  return [...groups.entries()].map(([workspaceId, list]) => ({
    workspaceId,
    name: list[0].workspaceName,
    backups: list,
  }));
}

function startError(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 409) {
    return "A backup or upload of this workspace is already running, or it needs a restart to pick up backups.";
  }
  if (failure instanceof ApiError && failure.status === 503) {
    return "The workspace is not running, or backups are not set up.";
  }
  return "Could not start the backup.";
}

function uploadError(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 400) {
    return "This file is not a .tar.gz archive. Upload one downloaded from a backup.";
  }
  if (failure instanceof ApiError && failure.status === 409) {
    return "A backup or upload of this workspace is already running.";
  }
  if (failure instanceof ApiError && failure.status === 503) {
    return "Backups are not set up.";
  }
  return "The upload failed. Please try again.";
}

function fromBackupError(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 422) return "The provider rejected this API key.";
  if (failure instanceof ApiError && failure.status === 400) return "Enter an API key: there is no workspace to reuse one from.";
  return "Could not create the workspace.";
}

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  type Backup,
  createWorkspaceFromBackup,
  deleteBackup,
  downloadBackup,
  listBackups,
  restoreBackup,
  type WorkspaceFromBackup,
} from "../api/backupsApi";
import { ApiError } from "../api/http";
import type { Workspace } from "../api/workspacesApi";

export type { Backup, WorkspaceFromBackup } from "../api/backupsApi";

/** One workspace's backups, newest first, under the name the left column shows. */
export interface BackupGroup {
  readonly workspaceId: string;
  readonly name: string;
  readonly exists: boolean;
  readonly backups: readonly Backup[];
}

/** "created", or why not: a key the provider refused, or one the form has to ask for. */
export type FromBackupResult = { readonly workspace: Workspace } | { readonly error: string };

/** Every backup the user has, grouped by the workspace it was made of. */
export function useBackups() {
  const [backups, setBackups] = useState<Backup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    return listBackups().then(
      (list) => {
        setBackups(list);
        setError(null);
      },
      () => setError("Could not load backups"),
    );
  }, []);

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

  // A backup still being made moves on its own; the list follows it until it settles.
  const running = backups.some((b) => b.status === "RUNNING");
  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => void refresh(), 2000);
    return () => clearInterval(timer);
  }, [running, refresh]);

  const groups = useMemo(() => groupBackups(backups), [backups]);

  const remove = (id: string): Promise<boolean> =>
    deleteBackup(id).then(
      () => {
        setBackups((current) => current.filter((b) => b.id !== id));
        return true;
      },
      () => {
        setError("Could not delete the backup");
        return false;
      },
    );

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

  return { groups, loading, error, dismissError: () => setError(null), refresh, remove, restore, download, createWorkspace };
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
    exists: list[0].workspaceExists,
    backups: list,
  }));
}

function fromBackupError(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 422) return "The provider rejected this API key.";
  if (failure instanceof ApiError && failure.status === 400) return "Enter an API key: there is no workspace to reuse one from.";
  return "Could not create the workspace.";
}

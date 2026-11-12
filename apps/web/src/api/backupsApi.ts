import { send } from "./http";
import { workspaceUrl, type Workspace } from "./workspacesApi";

export type BackupStatus = "RUNNING" | "COMPLETED" | "FAILED";

/** A workspace's whole home in storage, with the settings the database holds beside it. */
export interface Backup {
  readonly id: string;
  readonly workspaceId: string;
  /** The workspace's name now, or when the backup was made if it is gone. */
  readonly workspaceName: string;
  /** Only an existing workspace can be restored in place. */
  readonly workspaceExists: boolean;
  readonly status: BackupStatus;
  readonly phase: "preparing" | "archiving" | "uploading" | "done" | "failed" | string;
  /** 0 to 100. */
  readonly progress: number;
  readonly sizeBytes: number | null;
  readonly error: string | null;
  readonly createdAt: string;
  readonly completedAt: string | null;
  readonly modelProvider: string;
  readonly model: string | null;
  readonly sessionTtlEnabled: boolean;
  readonly sessionTtlHours: number;
  /** Names only: the values are never kept, so they have to be added again. */
  readonly secrets: readonly string[];
  readonly variables: readonly string[];
  readonly instructions: readonly string[];
  readonly tasks: readonly string[];
  readonly sessions: number;
}

export interface BackupDownload {
  readonly url: string;
  readonly expiresAt: string;
}

/** Without an apiKey the API reuses the key of the workspace the backup was made of. */
export interface WorkspaceFromBackup {
  readonly name: string;
  readonly apiKey?: string;
}

const BASE = "/api/v1/backups";
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

function backupUrl(id: string): string {
  if (!SAFE_ID.test(id)) {
    throw new Error("Invalid backup id");
  }
  return `${BASE}/${encodeURIComponent(id)}`;
}

export async function startBackup(workspaceId: string): Promise<Backup> {
  return (await send(`${workspaceUrl(workspaceId)}/backups`, { method: "POST" })).json();
}

export async function listBackups(): Promise<Backup[]> {
  return (await send(BASE)).json();
}

export async function getBackup(id: string): Promise<Backup> {
  return (await send(backupUrl(id))).json();
}

export async function deleteBackup(id: string): Promise<void> {
  await send(backupUrl(id), { method: "DELETE" });
}

export async function restoreBackup(id: string): Promise<void> {
  await send(`${backupUrl(id)}/restore`, { method: "POST" });
}

export async function createWorkspaceFromBackup(id: string, request: WorkspaceFromBackup): Promise<Workspace> {
  return (await send(`${backupUrl(id)}/workspaces`, { method: "POST", body: JSON.stringify(request) })).json();
}

export async function downloadBackup(id: string): Promise<BackupDownload> {
  return (await send(`${backupUrl(id)}/download`, { method: "POST" })).json();
}

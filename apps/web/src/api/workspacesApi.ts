import { send } from "./http";

export { ApiError } from "./http";

export type WorkspaceStatus = "INITIALIZING" | "READY" | "FAILED";

export interface Workspace {
  readonly id: string;
  readonly name: string;
  readonly modelProvider: string;
  /** The model its agent replies with; null for the harness's default. */
  readonly model: string | null;
  readonly status: WorkspaceStatus;
  /** Secrets changed since its runtime started; they apply only once it restarts. */
  readonly restartRequired: boolean;
}

export interface NewWorkspace {
  readonly name: string;
  /** One of /vm-sizes. */
  readonly serverType: string;
}

/** Every workspace uses OpenRouter until choosing a provider and its key comes back. */
export const MODEL_PROVIDER = "openrouter";

/** Sent once a workspace's deletion has committed, to every open stream. */
export interface DeletedEvent {
  readonly id: string;
}

/** One `status` event per change, after a snapshot of every workspace on connect. */
export interface StatusEvent {
  readonly id: string;
  readonly status: WorkspaceStatus;
}

const BASE = "/api/v1/workspaces";
export const EVENTS_URL = `${BASE}/events`;

// The server assigns UUIDs, and an id becomes a path segment of the request URL.
// Accepting only path-safe characters keeps a hostile response from steering the
// request at another endpoint.
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

export function workspaceUrl(id: string): string {
  if (!SAFE_ID.test(id)) {
    throw new Error("Invalid workspace id");
  }
  return `${BASE}/${encodeURIComponent(id)}`;
}

export async function listWorkspaces(): Promise<Workspace[]> {
  return (await send(BASE)).json();
}

export async function createWorkspace(workspace: NewWorkspace): Promise<Workspace> {
  const body = JSON.stringify({ ...workspace, modelProvider: MODEL_PROVIDER });
  return (await send(BASE, { method: "POST", body })).json();
}

export async function renameWorkspace(id: string, name: string): Promise<Workspace> {
  return (await send(workspaceUrl(id), { method: "PUT", body: JSON.stringify({ name }) })).json();
}

/** Answers once the VM is rebooting; the event stream reports it starting again. */
export async function restartWorkspace(id: string): Promise<void> {
  await send(`${workspaceUrl(id)}/restart`, { method: "POST" });
}

export async function deleteWorkspace(id: string): Promise<void> {
  await send(workspaceUrl(id), { method: "DELETE" });
}

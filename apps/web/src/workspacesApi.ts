export type WorkspaceStatus = "INITIALIZING" | "READY" | "FAILED";

export interface Workspace {
  readonly id: string;
  readonly name: string;
  readonly status: WorkspaceStatus;
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

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
  }
}

async function send(url: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    throw new ApiError(response.status);
  }
  return response;
}

function workspaceUrl(id: string): string {
  if (!SAFE_ID.test(id)) {
    throw new Error("Invalid workspace id");
  }
  return `${BASE}/${encodeURIComponent(id)}`;
}

export async function listWorkspaces(): Promise<Workspace[]> {
  return (await send(BASE)).json();
}

export async function createWorkspace(name: string): Promise<Workspace> {
  return (await send(BASE, { method: "POST", body: JSON.stringify({ name }) })).json();
}

export async function renameWorkspace(id: string, name: string): Promise<Workspace> {
  return (await send(workspaceUrl(id), { method: "PUT", body: JSON.stringify({ name }) })).json();
}

export async function deleteWorkspace(id: string): Promise<void> {
  await send(workspaceUrl(id), { method: "DELETE" });
}

import { send } from "./http";

export { ApiError } from "./http";

/** PAUSED: its VM is shut down, with everything kept, because the balance ran out. */
export type WorkspaceStatus = "INITIALIZING" | "READY" | "PAUSED" | "FAILED";

export interface Workspace {
  readonly id: string;
  readonly name: string;
  readonly modelProvider: string;
  /** The model its agent replies with; null for the harness's default. */
  readonly model: string | null;
  /** The IANA zone its agent reads times in, as USER_TIMEZONE. */
  readonly timeZone: string;
  readonly status: WorkspaceStatus;
  /** Settings changed since its runtime started; they apply only once it restarts. */
  readonly restartRequired: boolean;
  /** The account's free demo, which is never charged and is deleted at expiresAt. */
  readonly demo?: boolean;
  /** The plan's server type its VM runs on; null while it has none. */
  readonly serverType?: string | null;
  /** ISO 8601; null until a demo's VM has started, and for every other workspace. */
  readonly expiresAt?: string | null;
}

/** The key is checked with the provider before anything is created, and never shown again. */
export interface NewWorkspace {
  readonly name: string;
  /** One of /vm-sizes. */
  readonly serverType: string;
  /** One of /model-providers. */
  readonly modelProvider: string;
  readonly apiKey: string;
  readonly connectorId?: string;
  /** The IANA zone its agent reads times in; the API uses UTC when it is missing. */
  readonly timeZone?: string;
  /** The account's one free demo: the API ignores serverType and charges nothing. */
  readonly demo?: boolean;
}

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
  return (await send(BASE, { method: "POST", body: JSON.stringify({ ...workspace, apiKey: workspace.connectorId ? undefined : workspace.apiKey }) })).json();
}

/**
 * A workspace with an id but no VM or key, never listed, for a key that can be issued only once
 * the id exists. Only the id is of use until {@link launchWorkspace}.
 */
export async function createDraftWorkspace(workspace: Omit<NewWorkspace, "apiKey">): Promise<{ readonly id: string }> {
  return (await send(BASE, { method: "POST", body: JSON.stringify(workspace) })).json();
}

/** Creates a draft's VM with its key; a rejected key leaves it a draft to launch again. */
export async function launchWorkspace(id: string, apiKey: string, connectorId?: string): Promise<Workspace> {
  return (await send(`${workspaceUrl(id)}/launch`, { method: "POST", body: JSON.stringify(connectorId ? { connectorId } : { apiKey }) })).json();
}

export async function renameWorkspace(id: string, name: string): Promise<Workspace> {
  return (await send(workspaceUrl(id), { method: "PUT", body: JSON.stringify({ name }) })).json();
}

/** The harness reads it from its next start, so the workspace comes back needing a restart. */
export async function setWorkspaceTimeZone(id: string, timeZone: string): Promise<Workspace> {
  return (await send(`${workspaceUrl(id)}/time-zone`, { method: "PUT", body: JSON.stringify({ timeZone }) })).json();
}

/** Answers once the VM is rebooting; the event stream reports it starting again. */
export async function restartWorkspace(id: string): Promise<void> {
  await send(`${workspaceUrl(id)}/restart`, { method: "POST" });
}

export async function deleteWorkspace(id: string): Promise<void> {
  await send(workspaceUrl(id), { method: "DELETE" });
}

/** Turns the free demo into a workspace the balance pays for, on the same VM. */
export async function keepWorkspace(id: string): Promise<Workspace> {
  return (await send(`${workspaceUrl(id)}/keep`, { method: "POST" })).json();
}

/** Records tutorial completion once and sends an in-app notification, without email. */
export async function completeWorkspaceTutorial(id: string): Promise<void> {
  await send(`${workspaceUrl(id)}/tutorial-completed`, { method: "POST" }, { retry: "idempotent" });
}

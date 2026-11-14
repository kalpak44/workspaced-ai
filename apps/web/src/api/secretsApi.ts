import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

export interface Secret {
  readonly name: string;
  /** Set by the workspace's model provider; it cannot be removed. */
  readonly managed: boolean;
}

// The API accepts only environment variable names, and a name becomes a path segment,
// so anything else is refused before it can steer the request elsewhere.
export const SECRET_NAME = /^[A-Za-z_]\w*$/;

/** How many a user may add to one workspace; the provider's key does not count. */
export const MAX_SECRETS = 50;

function secretsUrl(workspaceId: string): string {
  return `${workspaceUrl(workspaceId)}/secrets`;
}

function secretUrl(workspaceId: string, name: string): string {
  if (!SECRET_NAME.test(name)) {
    throw new Error("Invalid secret name");
  }
  return `${secretsUrl(workspaceId)}/${encodeURIComponent(name)}`;
}

export async function listSecrets(workspaceId: string): Promise<Secret[]> {
  return (await send(secretsUrl(workspaceId))).json();
}

export async function addSecret(workspaceId: string, name: string, value: string): Promise<Secret> {
  const body = JSON.stringify({ name, value });
  return (await send(secretsUrl(workspaceId), { method: "POST", body })).json();
}

export async function removeSecret(workspaceId: string, name: string): Promise<void> {
  await send(secretUrl(workspaceId, name), { method: "DELETE" });
}

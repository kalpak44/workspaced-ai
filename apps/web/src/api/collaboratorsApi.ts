import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** OWNER created the workspace; APPLIED accepted an invitation; PENDING has not yet. */
export type CollaboratorStatus = "OWNER" | "APPLIED" | "PENDING";

/** Someone on a workspace, or an open invitation, which shows no address. */
export interface Collaborator {
  /** Null for an invitation. */
  readonly userId: string | null;
  /** Null for anyone who has joined. */
  readonly invitationId: string | null;
  readonly email: string | null;
  readonly status: CollaboratorStatus;
  readonly invitedBy: string | null;
  /** ISO 8601: when they joined, or when the invitation was sent. */
  readonly since: string | null;
  /** ISO 8601: when an invitation stops working. */
  readonly expiresAt: string | null;
}

// Ids become path segments, so only path-safe ones are sent.
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

function safe(id: string): string {
  if (!SAFE_ID.test(id)) throw new Error("Invalid id");
  return encodeURIComponent(id);
}

export async function listCollaborators(workspaceId: string): Promise<Collaborator[]> {
  return (await send(`${workspaceUrl(workspaceId)}/collaborators`)).json();
}

export async function inviteCollaborator(workspaceId: string, email: string): Promise<Collaborator> {
  const body = JSON.stringify({ email });
  return (await send(`${workspaceUrl(workspaceId)}/invitations`, { method: "POST", body })).json();
}

export async function revokeInvitation(workspaceId: string, invitationId: string): Promise<void> {
  await send(`${workspaceUrl(workspaceId)}/invitations/${safe(invitationId)}`, { method: "DELETE" });
}

/** Removing oneself is leaving the workspace. */
export async function removeCollaborator(workspaceId: string, userId: string): Promise<void> {
  await send(`${workspaceUrl(workspaceId)}/collaborators/${safe(userId)}`, { method: "DELETE" });
}

/** The code travels in the body, so it stays out of access logs. */
export async function acceptInvitation(code: string): Promise<{ readonly workspaceId: string }> {
  const body = JSON.stringify({ code });
  return (await send("/api/v1/invitations/accept", { method: "POST", body })).json();
}

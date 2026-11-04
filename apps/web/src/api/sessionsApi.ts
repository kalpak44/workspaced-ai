import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

export interface Session {
  readonly id: string;
  /** Null until the agent names the session after its first exchange. */
  readonly title: string | null;
  readonly path: string;
  /** ISO 8601. */
  readonly createdAt: string;
  readonly available?: boolean;
}

export interface ChatPart {
  readonly id: string;
  readonly type: "text" | "reasoning" | "tool";
  /** The text so far, or for a tool what it is doing. */
  readonly text: string | null;
  readonly tool: string | null;
  readonly status: "pending" | "running" | "completed" | "error" | null;
}

export interface ChatMessage {
  readonly id: string;
  readonly role: "user" | "assistant";
  readonly parts: readonly ChatPart[];
}

/** The events a session's stream sends, by event name. */
export interface SessionEvents {
  readonly message: { readonly id: string; readonly role: ChatMessage["role"] };
  readonly part: { readonly messageId: string; readonly part: ChatPart };
  readonly delta: { readonly messageId: string; readonly partId: string; readonly delta: string };
  readonly status: { readonly busy: boolean };
  readonly title: { readonly title: string };
  readonly error: { readonly message: string };
  readonly refresh: Record<string, never>;
}

// The server assigns UUIDs; anything else is refused before it becomes a path segment.
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

function sessionsUrl(workspaceId: string): string {
  return `${workspaceUrl(workspaceId)}/sessions`;
}

function sessionUrl(workspaceId: string, sessionId: string): string {
  if (!SAFE_ID.test(sessionId)) {
    throw new Error("Invalid session id");
  }
  return `${sessionsUrl(workspaceId)}/${encodeURIComponent(sessionId)}`;
}

export function sessionEventsUrl(workspaceId: string, sessionId: string): string {
  return `${sessionUrl(workspaceId, sessionId)}/events`;
}

export async function listSessions(workspaceId: string): Promise<Session[]> {
  return (await send(sessionsUrl(workspaceId))).json();
}

export async function createSession(workspaceId: string): Promise<Session> {
  return (await send(sessionsUrl(workspaceId), { method: "POST" })).json();
}

export async function deleteSession(workspaceId: string, sessionId: string): Promise<void> {
  await send(sessionUrl(workspaceId, sessionId), { method: "DELETE" });
}

export async function listMessages(workspaceId: string, sessionId: string): Promise<ChatMessage[]> {
  return (await send(`${sessionUrl(workspaceId, sessionId)}/messages`)).json();
}

export async function sendMessage(workspaceId: string, sessionId: string, text: string): Promise<void> {
  const body = JSON.stringify({ text });
  await send(`${sessionUrl(workspaceId, sessionId)}/messages`, { method: "POST", body });
}

export async function abortSession(workspaceId: string, sessionId: string): Promise<void> {
  await send(`${sessionUrl(workspaceId, sessionId)}/abort`, { method: "POST" });
}

/** Replaces a user turn and later history, then generates a fresh response. */
export async function editMessage(workspaceId: string, sessionId: string, messageId: string, text: string): Promise<void> {
  if (!SAFE_ID.test(messageId)) throw new Error("Invalid message id");
  await send(`${sessionUrl(workspaceId, sessionId)}/messages/${encodeURIComponent(messageId)}`, {
    method: "PUT", body: JSON.stringify({ text }),
  });
}

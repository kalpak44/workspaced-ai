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
  /** For a tool, the arguments it was called with, once known. */
  readonly input?: Readonly<Record<string, unknown>> | null;
  /** For a finished tool, what it printed, or why it failed. */
  readonly output?: string | null;
}

export interface ChatMessage {
  readonly id: string;
  readonly role: "user" | "assistant";
  readonly parts: readonly ChatPart[];
}

export type FormValue = string | number | boolean | readonly string[];
export type FormAnswer = Readonly<Record<string, FormValue>>;

export interface FormField {
  readonly key: string;
  readonly type: "string" | "number" | "integer" | "boolean" | "multiselect" | "external";
  readonly title?: string;
  readonly description?: string;
  readonly required?: boolean;
  readonly hidden?: boolean;
  readonly when?: readonly { readonly key: string; readonly op: "eq" | "neq"; readonly value: FormValue }[];
  readonly default?: FormValue;
  readonly options?: readonly { readonly value: string; readonly label: string; readonly description?: string }[];
  readonly custom?: boolean;
  readonly placeholder?: string;
  readonly format?: "email" | "uri" | "date" | "date-time";
  readonly minimum?: number;
  readonly maximum?: number;
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly pattern?: string;
  readonly minItems?: number;
  readonly maxItems?: number;
  readonly url?: string;
}

export interface AgentForm {
  readonly id: string;
  readonly title: string;
  readonly fields: readonly FormField[];
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
  readonly forms: Record<string, never>;
}

// The server assigns UUIDs; anything else is refused before it becomes a path segment.
const SAFE_ID = /^[A-Za-z0-9_-]+$/;

function sessionsUrl(workspaceId: string): string {
  return `${workspaceUrl(workspaceId)}/sessions`;
}

export function sessionUrl(workspaceId: string, sessionId: string): string {
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

export async function listForms(workspaceId: string, sessionId: string): Promise<AgentForm[]> {
  return (await send(`${sessionUrl(workspaceId, sessionId)}/forms`)).json();
}

export async function settleForm(workspaceId: string, sessionId: string, formId: string, answer?: FormAnswer): Promise<void> {
  if (!SAFE_ID.test(formId)) throw new Error("Invalid form id");
  const url = `${sessionUrl(workspaceId, sessionId)}/forms/${encodeURIComponent(formId)}`;
  await send(answer === undefined ? url : `${url}/reply`, answer === undefined
    ? { method: "DELETE" }
    : { method: "POST", body: JSON.stringify({ answer }) });
}

/** Replaces a user turn and later history, then generates a fresh response. */
export async function editMessage(workspaceId: string, sessionId: string, messageId: string, text: string): Promise<void> {
  if (!SAFE_ID.test(messageId)) throw new Error("Invalid message id");
  await send(`${sessionUrl(workspaceId, sessionId)}/messages/${encodeURIComponent(messageId)}`, {
    method: "PUT", body: JSON.stringify({ text }),
  });
}

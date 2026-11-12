import { send } from "./http";
import { sessionUrl } from "./sessionsApi";

/** A file uploaded into a session's folder; `path` is relative to that folder. */
export interface SessionFile {
  readonly name: string;
  readonly path: string;
  readonly size: number;
}

export interface FileLink {
  readonly url: string;
  readonly expiresAt: string;
}

/** The API's own rule for a name, so a file is renamed here rather than refused there. */
export function safeFileName(name: string): string {
  const cleaned = name.replace(/[^A-Za-z0-9._ ()+-]/g, "_").replace(/\.{2,}/g, ".").replace(/^[^A-Za-z0-9]+/, "");
  return (cleaned || "file").slice(0, 200);
}

/** The body is the file itself, streamed into the workspace. */
export async function uploadSessionFile(workspaceId: string, sessionId: string, file: File): Promise<SessionFile> {
  const url = `${sessionUrl(workspaceId, sessionId)}/files/${encodeURIComponent(safeFileName(file.name))}`;
  return (await send(url, { method: "PUT", body: file, headers: { "Content-Type": "application/octet-stream" } })).json();
}

export async function linkSessionFile(workspaceId: string, sessionId: string, path: string): Promise<FileLink> {
  return (await send(`${sessionUrl(workspaceId, sessionId)}/files/links`, { method: "POST", body: JSON.stringify({ path }) })).json();
}

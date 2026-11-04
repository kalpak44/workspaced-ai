import { send } from "./http";

export interface TerminalSession {
  readonly id: string;
  readonly title: string;
  readonly command: string;
  readonly cwd: string;
  readonly status: "running" | "exited";
  readonly pid: number;
  readonly exitCode: number | null;
}

const base = (workspaceId: string) => `/api/v1/workspaces/${workspaceId}/terminals`;

export async function listTerminals(workspaceId: string): Promise<TerminalSession[]> {
  return (await send(base(workspaceId))).json();
}

export async function createTerminal(workspaceId: string, title: string): Promise<TerminalSession> {
  return (await send(base(workspaceId), { method: "POST", body: JSON.stringify({ title }) })).json();
}

export async function deleteTerminal(workspaceId: string, id: string): Promise<void> {
  await send(`${base(workspaceId)}/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export function terminalSocketUrl(workspaceId: string, id: string): string {
  const protocol = globalThis.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${globalThis.location.host}${base(workspaceId)}/${encodeURIComponent(id)}/connect`;
}

import { send } from "./http";

export interface SessionAgent {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  readonly mode: "primary" | "all";
}

export interface AgentSelection {
  readonly current: string | null;
  readonly agents: readonly SessionAgent[];
}

function agentUrl(workspaceId: string, sessionId: string) {
  return `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/sessions/${encodeURIComponent(sessionId)}/agent`;
}

export async function getSessionAgents(workspaceId: string, sessionId: string): Promise<AgentSelection> {
  return (await send(agentUrl(workspaceId, sessionId))).json();
}

export async function setSessionAgent(workspaceId: string, sessionId: string, agent: string): Promise<AgentSelection> {
  return (await send(agentUrl(workspaceId, sessionId), { method: "PUT", body: JSON.stringify({ agent }) })).json();
}

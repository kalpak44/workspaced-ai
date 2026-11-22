import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";
export interface AiConnector { readonly id: string; readonly name: string; readonly provider: string; readonly trial?: boolean }
const BASE = "/api/v1/ai-connectors";
export async function listAiConnectors(): Promise<AiConnector[]> { return (await send(BASE)).json(); }
export async function createAiConnector(name: string, provider: string, apiKey: string, providerNoticeAccepted: boolean): Promise<AiConnector> {
  return (await send(BASE, { method: "POST", body: JSON.stringify({ name: name.trim(), provider, apiKey: apiKey.trim(), providerNoticeAccepted }) })).json();
}
export async function deleteAiConnector(id: string): Promise<void> { await send(`${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" }); }
export async function selectedAiConnector(id: string): Promise<AiConnector | null> {
  const response = await send(`${workspaceUrl(id)}/ai-connector`);
  return response.status === 204 ? null : response.json();
}
export async function selectAiConnector(workspaceId: string, connectorId: string): Promise<AiConnector> {
  return (await send(`${workspaceUrl(workspaceId)}/ai-connector`, { method: "PUT", body: JSON.stringify({ connectorId }) })).json();
}

export interface ConnectorMetrics {
  readonly usage: number | null;
  readonly usageDaily: number | null;
  readonly usageWeekly: number | null;
  readonly usageMonthly: number | null;
  readonly limit: number | null;
  readonly limitRemaining: number | null;
  readonly limitReset: string | null;
  readonly expiresAt: string | null;
  readonly includeByokInLimit: boolean | null;
  readonly byokUsage: number | null;
  readonly freeTier: boolean | null;
  readonly freeRequestsLimit: number | null;
  readonly freeRequestsRemaining: number | null;
}
export async function readConnectorMetrics(id: string, signal?: AbortSignal): Promise<ConnectorMetrics> {
  return (await send(`${BASE}/${encodeURIComponent(id)}/metrics`, { signal })).json();
}

export async function acknowledgeConnector(id: string): Promise<void> {
  await send(`${BASE}/${encodeURIComponent(id)}/acknowledgement`, { method: "POST", body: JSON.stringify({ accepted: true }) });
}

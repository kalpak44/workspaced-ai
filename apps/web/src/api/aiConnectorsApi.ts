import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";
export interface AiConnector { readonly id: string; readonly name: string; readonly provider: string }
const BASE = "/api/v1/ai-connectors";
export async function listAiConnectors(): Promise<AiConnector[]> { return (await send(BASE)).json(); }
export async function createAiConnector(name: string, provider: string, apiKey: string): Promise<AiConnector> {
  return (await send(BASE, { method: "POST", body: JSON.stringify({ name: name.trim(), provider, apiKey: apiKey.trim() }) })).json();
}
export async function deleteAiConnector(id: string): Promise<void> { await send(`${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" }); }
export async function selectedAiConnector(id: string): Promise<AiConnector | null> {
  const response = await send(`${workspaceUrl(id)}/ai-connector`);
  return response.status === 204 ? null : response.json();
}
export async function selectAiConnector(workspaceId: string, connectorId: string): Promise<AiConnector> {
  return (await send(`${workspaceUrl(workspaceId)}/ai-connector`, { method: "PUT", body: JSON.stringify({ connectorId }) })).json();
}

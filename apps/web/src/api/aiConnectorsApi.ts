import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";
export interface AiConnector { readonly id: string; readonly name: string; readonly provider: string; readonly trial?: boolean; readonly authType?: "api_key" | "chatgpt"; readonly workspaceId?: string | null }
const BASE = "/api/v1/ai-connectors";
export interface ChatGptAttempt { readonly id: string; readonly url: string; readonly instructions: string; readonly expiresAt: string; readonly pollSeconds: number }
export interface ChatGptStatus { readonly status: "pending" | "complete" | "cancelled" | "expired"; readonly connectorId: string | null }
export async function connectChatGpt(id: string, name: string, providerNoticeAccepted: boolean): Promise<ChatGptAttempt> {
  return (await send(`${workspaceUrl(id)}/chatgpt`, { method: "POST", body: JSON.stringify({ name: name.trim(), providerNoticeAccepted }) })).json();
}
export async function pollChatGpt(id: string, attempt: string): Promise<ChatGptStatus> {
  return (await send(`${workspaceUrl(id)}/chatgpt/${encodeURIComponent(attempt)}`)).json();
}
export async function cancelChatGpt(id: string, attempt: string): Promise<void> {
  await send(`${workspaceUrl(id)}/chatgpt/${encodeURIComponent(attempt)}`, { method: "DELETE" });
}
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

export interface ProviderBindingStatus {
  readonly active: AiConnector | null;
  readonly pending: AiConnector | null;
  readonly choices: readonly AiConnector[];
  readonly phase: "NONE" | "STAGED" | "APPLYING" | "ROLLING_BACK" | "BLOCKED";
  readonly error: string | null;
  readonly restartRequired: boolean;
}
export async function readProviderBindingStatus(id: string): Promise<ProviderBindingStatus> {
  return (await send(`${workspaceUrl(id)}/ai-connector/status`)).json();
}

export interface WorkspacedUsage {
  readonly workspaceId: string;
  readonly workspaceName: string;
  readonly connectorId: string | null;
  readonly provider: string;
  readonly model: string;
  readonly steps: number;
  readonly input: number;
  readonly output: number;
  readonly reasoning: number;
  readonly cacheRead: number;
  readonly cacheWrite: number;
}
export async function readWorkspacedUsage(signal?: AbortSignal): Promise<WorkspacedUsage[]> {
  return (await send("/api/v1/ai-usage", { signal })).json();
}

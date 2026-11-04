import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

export interface ModelOption {
  readonly id: string;
  readonly name: string;
  readonly contextLength: number | null;
  /** USD per million tokens. */
  readonly inputCost: number | null;
  readonly outputCost: number | null;
}

export interface ModelCatalogue {
  readonly defaultModel: string | null;
  readonly models: readonly ModelOption[];
}

export type McpStatus = "connected" | "failed" | "needs_auth" | "disabled" | "pending" | "unknown";

export interface McpServer {
  readonly name: string;
  readonly status: McpStatus | string;
}

/** A remote server by url, or a local one the workspace starts by command. */
export type NewMcpServer =
  | {
      readonly name: string;
      readonly url: string;
      readonly headers: Readonly<Record<string, string>>;
    }
  | {
      readonly name: string;
      readonly command: string;
      readonly args?: readonly string[];
      /** Each value is {env:SECRET}, or empty for the secret of the variable's own name. */
      readonly env?: Readonly<Record<string, string>>;
    };

// Server names are slugs; anything else is refused before it becomes a path segment.
export const MCP_NAME = /^[a-z0-9][a-z0-9_-]{0,63}$/;

export async function listModels(workspaceId: string): Promise<ModelCatalogue> {
  return (await send(`${workspaceUrl(workspaceId)}/models`)).json();
}

/** Null returns the workspace to the harness's default. */
export async function setModel(workspaceId: string, model: string | null): Promise<void> {
  const body = JSON.stringify({ model });
  await send(`${workspaceUrl(workspaceId)}/model`, { method: "PUT", body });
}

export async function listMcpServers(workspaceId: string): Promise<McpServer[]> {
  return (await send(`${workspaceUrl(workspaceId)}/mcp-servers`)).json();
}

export async function addMcpServer(workspaceId: string, server: NewMcpServer): Promise<McpServer> {
  const body = JSON.stringify(server);
  return (await send(`${workspaceUrl(workspaceId)}/mcp-servers`, { method: "POST", body })).json();
}

export async function removeMcpServer(workspaceId: string, name: string): Promise<void> {
  await send(`${workspaceUrl(workspaceId)}/mcp-servers/${encodeURIComponent(name)}`, {
    method: "DELETE",
  });
}

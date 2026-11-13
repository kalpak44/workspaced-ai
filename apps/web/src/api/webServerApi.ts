import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** The workspace's nginx, public at https://<name>.<domain> while it has a name. */
export interface WebServer {
  /** Null when this instance offers no web server. */
  readonly domain: string | null;
  readonly name: string | null;
  readonly url: string | null;
  /** Whether the workspace's agent may turn it on, off and rename it. */
  readonly agentAllowed: boolean;
}

// The API's rule, one DNS label of up to 59 characters, checked here too so the form can say
// what is wrong first.
export const WEB_SERVER_NAME = /^[a-z0-9](?:[a-z0-9-]{0,57}[a-z0-9])?$/;

function webServerUrl(workspaceId: string): string {
  return `${workspaceUrl(workspaceId)}/web-server`;
}

export async function getWebServer(workspaceId: string): Promise<WebServer> {
  return (await send(webServerUrl(workspaceId))).json();
}

/** Turns it on under that name, or moves it there. */
export async function enableWebServer(workspaceId: string, name: string): Promise<WebServer> {
  const body = JSON.stringify({ name });
  return (await send(webServerUrl(workspaceId), { method: "PUT", body })).json();
}

export async function setWebServerAgentAllowed(workspaceId: string, allowed: boolean): Promise<WebServer> {
  const body = JSON.stringify({ allowed });
  return (await send(`${webServerUrl(workspaceId)}/agent`, { method: "PUT", body })).json();
}

export async function disableWebServer(workspaceId: string): Promise<void> {
  await send(webServerUrl(workspaceId), { method: "DELETE" });
}

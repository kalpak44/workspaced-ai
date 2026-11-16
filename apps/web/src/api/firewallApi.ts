import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

export type FirewallProtocol = "tcp" | "udp" | "icmp";

export interface FirewallRule {
  readonly protocol: FirewallProtocol;
  /** One port or a range such as 8000-8100; null for icmp. */
  readonly port: string | null;
  /** Empty lets in anyone. */
  readonly sourceIps: readonly string[];
  readonly description: string | null;
}

export interface WorkspaceFirewall {
  /** The VM's public IPv4; null until it has one. */
  readonly address: string | null;
  readonly agentAllowed: boolean;
  readonly rules: readonly FirewallRule[];
}

export async function getFirewall(workspaceId: string): Promise<WorkspaceFirewall> {
  return (await send(`${workspaceUrl(workspaceId)}/firewall`)).json();
}

export async function setFirewallRules(workspaceId: string, rules: readonly FirewallRule[]): Promise<WorkspaceFirewall> {
  const body = JSON.stringify({ rules });
  return (await send(`${workspaceUrl(workspaceId)}/firewall`, { method: "PUT", body })).json();
}

export async function setFirewallAgentAllowed(workspaceId: string, allowed: boolean): Promise<void> {
  await send(`${workspaceUrl(workspaceId)}/firewall/agent`, { method: "PUT", body: JSON.stringify({ allowed }) });
}

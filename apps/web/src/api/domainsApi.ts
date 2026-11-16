import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

/** One record the user's DNS provider needs. */
export interface DnsRecord {
  readonly type: "A" | "AAAA" | "CNAME";
  /** As a provider's form asks for it: @ for the domain itself. */
  readonly host: string;
  readonly name: string;
  readonly value: string;
}

export interface WorkspaceDomain {
  readonly id: string;
  readonly name: string;
  readonly domain: string;
  readonly host: string;
  /** DOMAIN_CONNECT when the user's DNS provider applies the records once they approve. */
  readonly mode: "DOMAIN_CONNECT" | "MANUAL";
  /** ACTIVE once public DNS resolves the name to the workspace. */
  readonly status: "PENDING" | "ACTIVE";
  readonly providerName: string | null;
  /** Where the user approves the records; null once active or in manual mode. */
  readonly connectUrl: string | null;
  readonly records: readonly DnsRecord[];
  readonly createdAt: string;
  readonly verifiedAt: string | null;
}

const url = (workspaceId: string) => `${workspaceUrl(workspaceId)}/domains`;

export async function listDomains(workspaceId: string): Promise<WorkspaceDomain[]> {
  return (await send(url(workspaceId))).json();
}

export async function attachDomain(workspaceId: string, name: string): Promise<WorkspaceDomain> {
  return (await send(url(workspaceId), { method: "POST", body: JSON.stringify({ name }) })).json();
}

/** Answers the records it had, which stay at the user's DNS provider until they remove them. */
export async function detachDomain(workspaceId: string, domainId: string): Promise<DnsRecord[]> {
  return (await send(`${url(workspaceId)}/${encodeURIComponent(domainId)}`, { method: "DELETE" })).json();
}

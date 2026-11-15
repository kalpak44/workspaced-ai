/** A domain the account has registered. */
export interface OwnedDomain {
  readonly name: string;
  readonly registeredAt: string;
  readonly expiresAt: string;
  readonly autoRenew: boolean;
}

/** One answer to a search: a name under one TLD, and what a year of it costs. */
export interface DomainOffer {
  readonly name: string;
  readonly available: boolean;
  /** Per year, in cents. */
  readonly priceCents: number;
}

// The API has no domain routes yet; these samples let the page be built and reviewed first.
// The API will forward searches and purchases to a registrar, so each function here becomes a
// send() to its route and the page stays as it is.
const OWNED: OwnedDomain[] = [
  { name: "pavel-labs.dev", registeredAt: "2026-03-12T10:00:00Z", expiresAt: "2027-03-12T10:00:00Z", autoRenew: true },
  { name: "workspaced-demo.online", registeredAt: "2025-12-01T10:00:00Z", expiresAt: "2026-12-01T10:00:00Z", autoRenew: false },
];

// The endings offered beside whatever was typed, and their yearly price in cents.
const SUGGESTED: ReadonlyArray<readonly [string, number]> = [
  ["com", 1299], ["dev", 1499], ["io", 3999], ["app", 1699], ["ai", 7999], ["online", 399],
];
// What a sample answer quotes for an ending outside the suggested ones.
const OTHER_PRICE_CENTS = 1999;

export async function listOwnedDomains(): Promise<OwnedDomain[]> {
  return OWNED;
}

/**
 * The typed domain first when it named an ending, then the suggested endings for the same name.
 * A hash stands in for the registry, so one name always answers alike.
 */
export async function searchDomains(label: string, tld: string | null): Promise<DomainOffer[]> {
  const endings = SUGGESTED.filter(([suggested]) => suggested !== tld);
  const exact = tld === null ? [] : [[tld, SUGGESTED.find(([s]) => s === tld)?.[1] ?? OTHER_PRICE_CENTS] as const];
  return [...exact, ...endings].map(([ending, priceCents]) => {
    const name = `${label}.${ending}`;
    const hash = [...name].reduce((sum, char) => (sum * 31 + char.charCodeAt(0)) >>> 0, 7);
    return { name, priceCents, available: !OWNED.some((d) => d.name === name) && hash % 3 !== 0 };
  });
}

export const RECORD_TYPES = ["A", "AAAA", "CNAME", "TXT", "MX"] as const;
export type RecordType = (typeof RECORD_TYPES)[number];

/** One DNS record of a domain; `name` is "@" for the domain itself. */
export interface DnsRecord {
  readonly id: string;
  readonly type: RecordType;
  readonly name: string;
  readonly content: string;
  /** Seconds; 1 means automatic. */
  readonly ttl: number;
  /** MX only. */
  readonly priority?: number;
}

export type DnsRecordInput = Omit<DnsRecord, "id">;

/** Where a subdomain made in simple mode points, so a workspace's web server can answer it. */
export const SIMPLE_TARGET = "edge.workspaced.online";

// Kept in memory, so edits last until the page reloads; the registrar will hold the real zone.
const RECORDS = new Map<string, DnsRecord[]>([
  ["pavel-labs.dev", [
    { id: "r1", type: "A", name: "@", content: "203.0.113.10", ttl: 1 },
    { id: "r2", type: "CNAME", name: "www", content: SIMPLE_TARGET, ttl: 1 },
    { id: "r3", type: "CNAME", name: "blog", content: SIMPLE_TARGET, ttl: 1 },
    { id: "r4", type: "MX", name: "@", content: "mail.pavel-labs.dev", ttl: 3600, priority: 10 },
    { id: "r5", type: "TXT", name: "@", content: "v=spf1 mx -all", ttl: 3600 },
  ]],
  ["workspaced-demo.online", [
    { id: "r6", type: "CNAME", name: "app", content: SIMPLE_TARGET, ttl: 1 },
  ]],
]);
let nextId = 100;

export async function listRecords(domain: string): Promise<DnsRecord[]> {
  return [...(RECORDS.get(domain) ?? [])];
}

/** Creates a record, or replaces the one `id` names. */
export async function saveRecord(domain: string, record: DnsRecordInput, id?: string): Promise<DnsRecord[]> {
  const records = RECORDS.get(domain) ?? [];
  const saved = { ...record, id: id ?? `r${nextId++}` };
  RECORDS.set(domain, id ? records.map((r) => (r.id === id ? saved : r)) : [...records, saved]);
  return listRecords(domain);
}

export async function removeRecord(domain: string, id: string): Promise<DnsRecord[]> {
  RECORDS.set(domain, (RECORDS.get(domain) ?? []).filter((r) => r.id !== id));
  return listRecords(domain);
}

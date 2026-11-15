import { Globe, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { type DnsRecord, SIMPLE_TARGET, useDnsRecords } from "../../hooks/useDnsRecords";
import { Button, IconButton, Spinner, TextField } from "../atoms";
import { SettingsSection, Tabs } from "../molecules";
import { DnsRecordDialog, TTLS } from "./DnsRecordDialog";

// Records that make a host reachable; simple mode shows these as subdomains.
const HOST_TYPES: ReadonlySet<string> = new Set(["A", "AAAA", "CNAME"]);
const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

/**
 * One domain's DNS. Simple mode only adds subdomains pointing at the platform; advanced mode
 * edits every record. Both read the same zone, so switching loses nothing.
 */
export function DomainDnsPanel({ domain }: { readonly domain: string }) {
  const [mode, setMode] = useState<"simple" | "advanced">("simple");
  const { records, loading, save, remove } = useDnsRecords(domain);

  return (
    <SettingsSection title={domain} description={mode === "simple" ? "Create subdomains that point at your workspaces." : "Manage every DNS record of this domain."}>
      <Tabs label={`DNS mode for ${domain}`} value={mode} onChange={setMode}
        tabs={[{ value: "simple", label: "Simple" }, { value: "advanced", label: "Advanced" }]} />
      {loading
        ? <div className="flex justify-center py-4"><Spinner className="size-5 text-zinc-400" /></div>
        : mode === "simple"
          ? <SimpleDns domain={domain} records={records} onCreate={(name) => save({ type: "CNAME", name, content: SIMPLE_TARGET, ttl: 1 })} onRemove={remove} />
          : <AdvancedDns domain={domain} records={records} onSave={save} onRemove={remove} />}
    </SettingsSection>
  );
}

interface SimpleProps {
  readonly domain: string;
  readonly records: readonly DnsRecord[];
  readonly onCreate: (name: string) => Promise<void>;
  readonly onRemove: (id: string) => Promise<void>;
}

function SimpleDns({ domain, records, onCreate, onRemove }: SimpleProps) {
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const subdomains = records.filter((r) => r.name !== "@" && HOST_TYPES.has(r.type));

  async function create() {
    const label = name.trim().toLowerCase();
    if (!LABEL.test(label)) return setError("Use letters, digits and hyphens, without a hyphen at either end.");
    // Any record already on the name would clash with a CNAME, not only another subdomain.
    if (records.some((r) => r.name === label)) return setError(`${label}.${domain} already exists.`);
    setError("");
    await onCreate(label);
    setName("");
  }

  return (
    <div className="space-y-4">
      <form noValidate onSubmit={(event) => { event.preventDefault(); void create(); }} className="flex items-start gap-2">
        <div className="flex min-w-0 flex-1 items-start gap-1.5">
          <div className="min-w-0 flex-1">
            <TextField id={`subdomain-${domain}`} label="New subdomain" placeholder="blog" value={name} error={error || null}
              onChange={(event) => { setName(event.target.value); setError(""); }} />
          </div>
          <span className="mt-9 shrink-0 truncate font-mono text-sm text-zinc-500 dark:text-zinc-400">.{domain}</span>
        </div>
        <div className="mt-6.5"><Button label="Create" icon={Plus} type="submit" disabled={name.trim() === ""} /></div>
      </form>
      {subdomains.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">No subdomains yet.</p>
      ) : (
        <ul aria-label={`Subdomains of ${domain}`} className="divide-y divide-zinc-200 dark:divide-zinc-800">
          {subdomains.map((record) => (
            <li key={record.id} className="flex items-center gap-3 py-2.5">
              <Globe aria-hidden className="size-4 shrink-0 text-zinc-400" />
              <span className="min-w-0 flex-1 truncate font-mono text-sm">{record.name}.{domain}</span>
              {/* A record made in advanced mode may point elsewhere; say so rather than hide it. */}
              {record.content !== SIMPLE_TARGET && <span className="hidden truncate text-xs text-zinc-500 sm:inline dark:text-zinc-400">→ {record.content}</span>}
              <IconButton label={`Remove ${record.name}.${domain}`} icon={Trash2} onClick={() => void onRemove(record.id)} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface AdvancedProps {
  readonly domain: string;
  readonly records: readonly DnsRecord[];
  readonly onSave: (record: Omit<DnsRecord, "id">, id?: string) => Promise<void>;
  readonly onRemove: (id: string) => Promise<void>;
}

function AdvancedDns({ domain, records, onSave, onRemove }: AdvancedProps) {
  // Undefined while closed, null to add, a record to edit it.
  const [editing, setEditing] = useState<DnsRecord | null | undefined>(undefined);
  const ttlLabel = (ttl: number) => TTLS.find(([seconds]) => seconds === ttl)?.[1] ?? `${ttl}s`;

  return (
    <div className="space-y-3">
      <div className="flex justify-end"><Button label="Add record" icon={Plus} variant="secondary" onClick={() => setEditing(null)} /></div>
      {records.length === 0 ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">No records yet.</p>
      ) : (
        <div className="-mx-4 overflow-x-auto sm:-mx-6">
          <table aria-label={`Records of ${domain}`} className="w-full text-left text-sm">
            <thead className="border-b border-zinc-200 text-xs text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium sm:pl-6">Type</th>
                <th scope="col" className="px-4 py-2 font-medium">Name</th>
                <th scope="col" className="px-4 py-2 font-medium">Content</th>
                <th scope="col" className="hidden px-4 py-2 font-medium sm:table-cell">TTL</th>
                <th scope="col" className="px-4 py-2 sm:pr-6"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {records.map((record) => (
                <tr key={record.id}>
                  <td className="px-4 py-2.5 sm:pl-6"><span className="rounded bg-zinc-100 px-1.5 py-0.5 font-mono text-xs font-medium dark:bg-zinc-800">{record.type}</span></td>
                  <td className="whitespace-nowrap px-4 py-2.5 font-mono text-xs">{record.name}</td>
                  <td className="max-w-56 truncate px-4 py-2.5 font-mono text-xs" title={record.content}>
                    {record.priority !== undefined && <span className="text-zinc-500 dark:text-zinc-400">{record.priority} </span>}
                    {record.content}
                  </td>
                  <td className="hidden whitespace-nowrap px-4 py-2.5 text-xs text-zinc-500 sm:table-cell dark:text-zinc-400">{ttlLabel(record.ttl)}</td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-right sm:pr-6">
                    <IconButton label={`Edit ${record.type} ${record.name}`} icon={Pencil} onClick={() => setEditing(record)} />
                    <IconButton label={`Delete ${record.type} ${record.name}`} icon={Trash2} onClick={() => void onRemove(record.id)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing !== undefined && (
        <DnsRecordDialog domain={domain} record={editing ?? undefined} onClose={() => setEditing(undefined)}
          onSave={async (record) => { await onSave(record, editing?.id); setEditing(undefined); }} />
      )}
    </div>
  );
}

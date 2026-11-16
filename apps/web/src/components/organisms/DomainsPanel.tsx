import { Copy, ExternalLink, Globe, Plus, ShieldCheck, Trash2, X } from "lucide-react";
import { type SubmitEvent, useEffect, useState } from "react";
import { type DnsRecord, useDomains, type WorkspaceDomain } from "../../hooks/useDomains";
import { type FirewallRule, useFirewall } from "../../hooks/useFirewall";
import { Button, IconButton, Spinner, Tag, TextField } from "../atoms";
import { ConfirmDeleteDialog, Modal, ModalBody, ModalFooter, SettingsSection } from "../molecules";

interface Props { readonly workspaceId: string }

// What a website needs open; whatever the workspace serves on them answers on the domain.
const WEB_PORTS = [
  { protocol: "tcp", port: "80", sourceIps: [], description: "Web (HTTP)" },
  { protocol: "tcp", port: "443", sourceIps: [], description: "Web (HTTPS)" },
] as const satisfies readonly FirewallRule[];

const opens = (rules: readonly FirewallRule[], port: string) =>
  rules.some((r) => r.protocol === "tcp" && r.port === port && (r.sourceIps.length === 0 || r.sourceIps.includes("0.0.0.0/0")));

/**
 * The user's own domains pointed at this workspace. A name only turns live once public DNS
 * reaches the VM; until then it shows what to do next, and can be cancelled outright.
 */
export function DomainsPanel({ workspaceId }: Props) {
  const { domains, error, busy, attach, detach, retry } = useDomains(workspaceId);
  const firewall = useFirewall(workspaceId);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<WorkspaceDomain | null>(null);
  const [leftover, setLeftover] = useState<{ name: string; records: readonly DnsRecord[] } | null>(null);
  const [problem, setProblem] = useState("");
  const [notice, setNotice] = useState("");

  // A DNS provider sends the user back here, with error= when they did not approve.
  useEffect(() => {
    const params = new URLSearchParams(globalThis.location.search);
    const refused = params.get("error");
    if (!refused && !params.has("state")) return;
    setProblem(refused ? `Your DNS provider did not add the records: ${params.get("error_description") ?? refused}.` : "");
    setNotice(refused ? "" : "Your DNS provider is adding the records. This turns live once they are visible.");
    globalThis.history.replaceState(globalThis.history.state, "", globalThis.location.pathname);
  }, []);

  const report = (failure: string, done: string) => { setProblem(failure); setNotice(failure ? "" : done); };

  // Nothing points here yet, so there is nothing to confirm and nothing to clean up.
  const cancel = async (domain: WorkspaceDomain) => {
    const records = await detach(domain.id);
    report(records ? "" : `Could not cancel ${domain.name}. Please try again.`, `Cancelled ${domain.name}.`);
  };

  const remove = async (domain: WorkspaceDomain) => {
    const records = await detach(domain.id);
    setRemoving(null);
    if (!records) return report(`Could not remove ${domain.name}. Please try again.`, "");
    report("", `Removed ${domain.name}.`);
    if (records.length > 0) setLeftover({ name: domain.name, records });
  };

  const live = domains?.some((d) => d.status === "ACTIVE") ?? false;
  const rules = firewall.firewall?.rules;
  const closed = live && rules !== undefined && WEB_PORTS.some((p) => !opens(rules, p.port));
  const openWeb = async () => {
    if (!rules) return;
    const kept = rules.filter((r) => !(r.protocol === "tcp" && (r.port === "80" || r.port === "443")));
    const failure = await firewall.saveRules([...kept, ...WEB_PORTS]);
    report(failure ?? "", "Ports 80 and 443 are open.");
  };

  return <SettingsSection title="Domains" description="Point a domain you own, or a name under it, at this workspace.">
    {domains === null ? (error
      ? <div className="space-y-3"><p role="alert" className="text-sm text-rose-600">{error}</p><Button label="Retry" variant="secondary" onClick={retry} /></div>
      : <Spinner label="Loading the domains" className="size-5 text-zinc-400" />) : <>
      {domains.length === 0
        ? <p className="text-sm text-zinc-500">No domains yet. A site you run in the workspace can answer on your own domain.</p>
        : <ul aria-label="Domains" className="divide-y divide-zinc-200 rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {domains.map((domain) => <DomainItem key={domain.id} domain={domain} busy={busy}
            onCancel={() => void cancel(domain)} onRemove={() => setRemoving(domain)} onCopied={() => setNotice("Copied.")} />)}
        </ul>}

      {closed && <div className="flex flex-wrap items-center gap-3 rounded-md bg-zinc-50 px-4 py-3 text-sm dark:bg-zinc-900/60">
        <ShieldCheck aria-hidden className="size-4 shrink-0 text-zinc-500" />
        <span className="min-w-0 flex-1">Your domain reaches the workspace. Open ports 80 and 443 so web traffic gets through.</span>
        <Button label="Open ports 80 and 443" variant="secondary" busy={firewall.saving} onClick={() => void openWeb()} />
      </div>}

      <div><Button label="Add domain" icon={Plus} onClick={() => { setAdding(true); setProblem(""); setNotice(""); }} /></div>
    </>}
    {problem && <p role="alert" className="text-sm text-rose-600">{problem}</p>}
    {notice && <p role="status" className="text-sm text-zinc-500">{notice}</p>}

    {adding && <AddDomainDialog busy={busy} onClose={() => setAdding(false)} onAdd={async (name) => {
      const failure = await attach(name);
      if (!failure) { setAdding(false); setNotice(`Added ${name}. Follow the steps below to point it here.`); }
      return failure;
    }} />}
    {removing && <ConfirmDeleteDialog title={`Remove ${removing.name}?`} description="It stops pointing at this workspace."
      confirmLabel="Remove domain" busy={busy} onClose={() => setRemoving(null)} onConfirm={() => void remove(removing)}>
      Its records stay at your DNS provider until you delete them there; you will be shown which ones.
    </ConfirmDeleteDialog>}
    {leftover && <Modal title="Remove these records" description={`At your DNS provider, so ${leftover.name} stops pointing at the workspace.`} onClose={() => setLeftover(null)}>
      <ModalBody><Records records={leftover.records} onCopied={() => setNotice("Copied.")} /></ModalBody>
      <ModalFooter><Button label="Done" autoFocus onClick={() => setLeftover(null)} /></ModalFooter>
    </Modal>}
  </SettingsSection>;
}

interface ItemProps {
  readonly domain: WorkspaceDomain;
  readonly busy: boolean;
  readonly onCancel: () => void;
  readonly onRemove: () => void;
  readonly onCopied: () => void;
}

function DomainItem({ domain, busy, onCancel, onRemove, onCopied }: ItemProps) {
  const pending = domain.status === "PENDING";
  return <li className="space-y-3 px-4 py-3 text-sm">
    <div className="flex items-center gap-3">
      <Globe aria-hidden className="size-4 shrink-0 text-zinc-400" />
      <a href={`https://${domain.name}`} target="_blank" rel="noreferrer" className="min-w-0 flex-1 truncate font-mono hover:underline">{domain.name}</a>
      <Tag label={pending ? "Waiting for DNS" : "Live"} tone={pending ? "plain" : "strong"} />
      {pending
        ? <IconButton label={`Cancel ${domain.name}`} icon={X} disabled={busy} onClick={onCancel} />
        : <IconButton label={`Remove ${domain.name}`} icon={Trash2} disabled={busy} onClick={onRemove} />}
    </div>
    {pending && <div className="space-y-3 rounded-md bg-zinc-50 p-3 dark:bg-zinc-900/60">
      {domain.connectUrl
        ? <>
          <p>{domain.providerName ?? "Your DNS provider"} can add the records for you. Approve them there, then come back here.</p>
          <a href={domain.connectUrl} className="inline-flex items-center gap-1.5 rounded-md bg-zinc-900 px-3 py-2 font-medium text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200">
            <ExternalLink aria-hidden className="size-4" />Connect at {domain.providerName ?? "your DNS provider"}
          </a>
          <details>
            <summary className="cursor-pointer text-zinc-500">Or add the records yourself</summary>
            <div className="mt-2"><Records records={domain.records} onCopied={onCopied} /></div>
          </details>
        </>
        : <>
          <p>Add {domain.records.length === 1 ? "this record" : "these records"} at your DNS provider:</p>
          <Records records={domain.records} onCopied={onCopied} />
        </>}
      <p className="text-xs text-zinc-500">We check about once a minute. Typed it wrong? Cancel it and add the right one.</p>
    </div>}
  </li>;
}

function Records({ records, onCopied }: { readonly records: readonly DnsRecord[]; readonly onCopied: () => void }) {
  const copy = (text: string) => { void navigator.clipboard?.writeText(text); onCopied(); };
  return <ul aria-label="DNS records" className="space-y-2">
    {records.map((record) => <li key={`${record.type}-${record.host}-${record.value}`}
      className="grid grid-cols-[3.5rem_minmax(0,1fr)] items-center gap-x-3 gap-y-1 rounded-md border border-zinc-200 bg-white px-3 py-2 dark:border-zinc-800 dark:bg-zinc-950 sm:grid-cols-[3.5rem_8rem_minmax(0,1fr)]">
      <span className="font-mono text-xs font-medium">{record.type}</span>
      <span className="flex min-w-0 items-center gap-1">
        <code className="truncate font-mono text-xs">{record.host}</code>
        <IconButton label={`Copy the name ${record.host}`} icon={Copy} onClick={() => copy(record.host)} />
      </span>
      <span className="col-span-2 flex min-w-0 items-center gap-1 sm:col-span-1">
        <code className="truncate font-mono text-xs" title={record.value}>{record.value}</code>
        <IconButton label={`Copy the value ${record.value}`} icon={Copy} onClick={() => copy(record.value)} />
      </span>
    </li>)}
  </ul>;
}

interface AddProps {
  readonly busy: boolean;
  readonly onClose: () => void;
  /** Answers the problem to show, or null once it is added. */
  readonly onAdd: (name: string) => Promise<string | null>;
}

function AddDomainDialog({ busy, onClose, onAdd }: AddProps) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    setError(await onAdd(name.trim()));
  };
  return <Modal title="Add a domain" description="A domain you own, or a name under it." onClose={() => { if (!busy) onClose(); }}>
    <form onSubmit={submit} className="contents">
      <ModalBody>
        <TextField id="domain-name" label="Domain" placeholder="example.com or app.example.com" autoFocus required
          autoComplete="off" spellCheck={false} value={name} error={error} disabled={busy}
          hint="A domain points the site itself and www here; a name such as app.example.com points just that name."
          onChange={(event) => { setName(event.currentTarget.value); setError(null); }} />
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" disabled={busy} onClick={onClose} />
        <Button label="Add domain" type="submit" busy={busy} disabled={name.trim() === ""} />
      </ModalFooter>
    </form>
  </Modal>;
}

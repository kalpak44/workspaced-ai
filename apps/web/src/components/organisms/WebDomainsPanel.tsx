import { useState } from "react";
import { ExternalLink } from "lucide-react";
import { useWebDomains, WEB_SERVER_NAME } from "../../hooks/useWebDomains";
import { Button } from "../atoms";

interface Props { readonly workspaceId: string; readonly domain: string; readonly revision: string | null; readonly onChange: () => void }

/** Every address reaches the same nginx; Host selects the user's server block. */
export function WebDomainsPanel({ workspaceId, domain, revision, onChange }: Props) {
  const [name, setName] = useState("");
  const { domains, busy, loading, error, setError, change, save, remove, retry } = useWebDomains(workspaceId, revision);
  const invalid = name !== "" && !WEB_SERVER_NAME.test(name);
  return <section aria-label="Public addresses" className="space-y-4 border-t border-zinc-200 pt-5 dark:border-zinc-800">
    <div><h3 className="text-sm font-medium">Public addresses</h3>
      <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">Each address uses HTTPS and reaches this workspace’s nginx on port 80. Use nginx server_name rules to route each hostname to a different app.</p></div>
    {loading ? <p role="status" className="text-sm text-zinc-500">Loading addresses…</p> : <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
      {domains.map(item => <li key={item.name} className="flex flex-wrap items-center justify-between gap-3 py-3">
        <div className="min-w-0 flex-1"><a href={item.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 break-all font-mono text-xs underline underline-offset-4">{item.url}<ExternalLink aria-hidden className="size-3 shrink-0" /></a>
          <span className="mt-1 block text-xs text-zinc-500">{item.enabled ? "Enabled" : "Disabled · name reserved"}</span></div>
        <div className="flex gap-2">
          <Button label={`${item.enabled ? "Disable" : "Enable"} ${item.name}`} variant="secondary" disabled={busy} onClick={() => void change(async () => { await save(item.name, !item.enabled); })} />
          <Button label={`Remove ${item.name}`} variant="secondary" disabled={busy} onClick={() => void change(async () => { await remove(item.name); onChange(); })} />
        </div>
      </li>)}
      {domains.length === 0 && <li className="py-3 text-sm text-zinc-500">No public addresses yet.</li>}
    </ul>}
    <form aria-label="Add public address" className="flex flex-wrap items-end gap-3" onSubmit={event => {
      event.preventDefault();
      if (!name || invalid) return;
      void change(async () => { await save(name, true); setName(""); });
    }}>
      <label className="min-w-0 flex-1 text-sm font-medium" htmlFor="new-web-domain">New address
        <div className="mt-1 flex items-center rounded-md border border-zinc-300 px-3 dark:border-zinc-700">
          <span aria-hidden className="font-mono text-xs text-zinc-500">box-</span>
          <input id="new-web-domain" value={name} disabled={busy || loading} onChange={event => { setName(event.target.value.toLowerCase().trim()); setError(""); }}
            maxLength={59} placeholder="my-app" autoComplete="off" spellCheck={false} aria-invalid={invalid || undefined} aria-describedby="new-domain-help"
            className="min-w-0 flex-1 bg-transparent py-2 font-mono text-sm outline-none" />
          <span aria-hidden className="hidden font-mono text-xs text-zinc-500 sm:inline">.{domain}</span>
        </div>
      </label>
      <Button label="Add address" type="submit" busy={busy} disabled={loading || !name || invalid || domains.some(d => d.name === name)} />
    </form>
    <p id="new-domain-help" className="text-xs text-zinc-500">{invalid ? "Use up to 59 lowercase letters, digits and inner hyphens." : "Disabling keeps the name reserved. Removing frees it for other workspaces. Other addresses keep working."}</p>
    {error && <div><p role="alert" className="text-sm text-rose-600">{error}</p><Button label="Reload addresses" variant="secondary" onClick={retry} /></div>}
  </section>;
}

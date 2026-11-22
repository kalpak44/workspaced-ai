import { useRef, useState } from "react";
import { Plug, Trash2 } from "lucide-react";
import { isRejectedConnectorKey, useAiConnectors } from "../../hooks/useAiConnectors";
import { useModelProviders } from "../../hooks/useModelProviders";
import { useOpenRouterConnect } from "../../hooks/useOpenRouterConnect";
import { Button, IconButton, Select, Spinner, TextField } from "../atoms";
import { AiProviderNotice } from "../molecules/AiProviderNotice";
import { Modal, ModalBody, ModalFooter } from "../molecules/Modal";
import { TrialConnectorBudget } from "./TrialConnectorBudget";
import { ConnectorUsage } from "./ConnectorUsage";

export function AiConnectorsPanel() {
  const { connectors, add, remove: removeConnector, loading, error: loadError } = useAiConnectors();
  const { providers } = useModelProviders();
  const [selectedId, setSelectedId] = useState("");
  const trial = connectors.find(connector => connector.trial);
  const personalConnectors = connectors.filter(connector => !connector.trial);
  const selected = personalConnectors.find(connector => connector.id === selectedId) ?? personalConnectors[0];
  const [name, setName] = useState("");
  const [providerId, setProviderId] = useState("openrouter");
  const [key, setKey] = useState("");
  const [accepted, setAccepted] = useState(false);
  const [limit, setLimit] = useState("");
  const [expiration, setExpiration] = useState("never");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const deleting = connectors.find(connector => connector.id === deleteId);
  const [removing, setRemoving] = useState<string | null>(null);
  const oauthName = useRef("");
  const limitValid = !limit || (/^\d+(\.\d{1,2})?$/.test(limit) && Number(limit) > 0 && Number(limit) < 1_000_000);

  const save = async (apiKey: string, connectorName = name) => {
    if (!accepted) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const connector = await add(connectorName, providerId, apiKey, accepted);
      setSelectedId(connector.id); setKey(""); setName(""); setAccepted(false);
      setNotice("AI connector saved. You can now select it in your workspaces.");
    } catch (cause) {
      setError(isRejectedConnectorKey(cause) ? "The provider rejected this API key." : "Could not save the AI connector. Please try again.");
    } finally { setBusy(false); }
  };
  const oauth = useOpenRouterConnect(apiKey => { setKey(apiKey); void save(apiKey, oauthName.current); });
  const connecting = oauth.status === "waiting" || oauth.status === "exchanging";
  const remove = async (id: string) => {
    setRemoving(id); setError("");
    try { await removeConnector(id); setDeleteId(null); setNotice("Connector removed from Workspaced. Affected workspaces now use Trial and need a restart. Your OpenRouter key has not been revoked."); }
    catch { setError("Could not delete the AI connector. Please try again."); }
    finally { setRemoving(null); }
  };

  return <section aria-label="AI connectors" className="space-y-6">
    <p className="text-sm leading-6 text-zinc-600 dark:text-zinc-400">Workspaced does not provide its own AI models. AI connectors use third-party providers through OpenRouter, under their terms and privacy policies. Connect your own provider account for ongoing use; provider charges are separate from workspace hosting. The Trial connector has a small one-time budget with no automatic refill.</p>
    <div className="card overflow-hidden">
      <div className="border-b border-zinc-200 p-5 dark:border-zinc-800">
        <h2 className="font-semibold">Your AI connectors</h2>
        <p className="mt-1 text-sm text-zinc-500">Named connections you can reuse across your workspaces. Select one to see its usage and limits. Keys are encrypted and never shown again.</p>
      </div>
      {trial && <div aria-label="Default Trial connector" className="space-y-3 border-b border-zinc-200 px-5 py-4 dark:border-zinc-800"><div className="flex items-center gap-2"><h3 className="font-medium">{trial.name}</h3><span className="rounded-md bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">Default · Trial</span></div><TrialConnectorBudget id={trial.id} /></div>}
      {loading ? <div className="p-5"><Spinner label="Loading AI connectors" /></div> : <div className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {connectors.length === 0 && <p className="p-5 text-sm text-zinc-500">No connectors yet. Add your first connection below.</p>}
        {personalConnectors.map(connector => <div key={connector.id} className={`flex items-center gap-3 px-5 py-3 ${selected?.id === connector.id ? "bg-zinc-50 dark:bg-zinc-900" : ""}`}>
          <button type="button" aria-pressed={selected?.id === connector.id} onClick={() => setSelectedId(connector.id)} className="min-w-0 flex-1 rounded-lg py-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-zinc-400">
            <span className="block break-words font-medium">{connector.name}</span>
            <span className="text-sm text-zinc-500">{providers.find(provider => provider.id === connector.provider)?.name ?? connector.provider}</span>
          </button>
          <IconButton label={`Delete ${connector.name}`} icon={Trash2} className="shrink-0" aria-busy={removing === connector.id} disabled={removing !== null} onClick={() => { setDeleteId(connector.id); setError(""); }} />
        </div>)}
      </div>}
      <p className="border-t border-zinc-200 px-5 py-3 text-xs text-zinc-500 dark:border-zinc-800">Trial is your protected default connector and cannot be deleted. Removing a personal connector switches workspaces using it to Trial; restart them to apply the change. Your personal key stays active in OpenRouter.</p>
    </div>
    {selected?.provider === "openrouter" && <ConnectorUsage key={selected.id} id={selected.id} name={selected.name} />}
    <form aria-label="Add AI connector" className="card space-y-4 p-5" onSubmit={event => { event.preventDefault(); void save(key); }}>
      <h2 className="font-semibold">Add AI connector</h2>
      <TextField id="connector-name" label="Connector name" placeholder="Personal, work, or a project name" maxLength={100} required value={name} disabled={busy || connecting} onChange={event => setName(event.target.value)} />
      <label className="block text-sm font-medium">Model provider
        <Select aria-label="Model provider" className="mt-1.5 w-full" value={providerId} disabled={busy || connecting} onChange={event => setProviderId(event.target.value)}>
          {providers.map(provider => <option key={provider.id} value={provider.id}>{provider.name}</option>)}
        </Select>
      </label>
      <AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={busy || connecting} />
      {providerId === "openrouter" && <div className="space-y-4 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
        <div><h3 className="text-sm font-semibold">Create a new OpenRouter key</h3><p className="mt-1 text-xs leading-5 text-zinc-500">These settings prefill OpenRouter’s approval screen. Confirm the final values there. They do not change an existing key you paste below.</p></div>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField id="connector-limit" label="Suggested spending limit (USD)" inputMode="decimal" placeholder="No suggested limit" value={limit} disabled={busy || connecting} error={limitValid ? null : "Use $0.01–$999,999.99, with up to two decimal places."} onChange={event => setLimit(event.target.value)} />
          <label className="block text-sm font-medium">Suggested expiry
            <Select aria-label="Suggested expiry" className="mt-1.5 w-full" value={expiration} disabled={busy || connecting} onChange={event => setExpiration(event.target.value)}>
              <option value="never">No expiry</option><option value="1-day">1 day</option><option value="7-days">7 days</option><option value="30-days">30 days</option><option value="90-days">90 days</option><option value="1-year">1 year</option>
            </Select>
          </label>
        </div>
        <Button label="Connect OpenRouter" icon={Plug} variant="secondary" busy={oauth.status === "exchanging"} disabled={!name.trim() || !accepted || !limitValid || busy} onClick={() => {
          if (!accepted || !limitValid) return;
          oauthName.current = name.trim();
          oauth.connect(async () => `workspaced: ${oauthName.current}`, { limit: limit || undefined, expiration });
        }} />
      </div>}
      {connecting && <p role="status" className="text-sm text-zinc-500">{oauth.status === "waiting" ? "Approve the key in the OpenRouter window." : "Getting your key from OpenRouter…"}</p>}
      {(oauth.status === "blocked" || oauth.status === "failed") && <p role="alert" className="text-sm text-rose-600">{oauth.status === "blocked" ? "Allow pop-ups for this site and try again." : "OpenRouter did not issue a key. Try again or paste a key."}</p>}
      <TextField id="connector-key" label="API key" hint="Or paste an existing OpenRouter key. Its current limits and expiry stay in place." type="password" autoComplete="off" maxLength={1000} value={key} disabled={busy || connecting} onChange={event => setKey(event.target.value)} />
      <Button label="Save connector" type="submit" busy={busy} disabled={!name.trim() || !key.trim() || !accepted || connecting} />
    </form>
    {(error || loadError) && <p role="alert" className="text-sm text-rose-600">{error || loadError}</p>}
    {notice && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">{notice}</p>}
    {deleting && <Modal title="Remove AI connector?" description={deleting.name} onClose={() => { if (!removing) setDeleteId(null); }}>
      <ModalBody>
        <p>This removes <strong>{deleting.name}</strong> from your Workspaced account only.</p>
        <p className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm leading-6 text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200">The API key will stay active in your OpenRouter account. Workspaces using this connector will switch to Trial and require a restart. Trial uses whatever is left of its one-time budget; removing a connector does not refill it. To revoke the personal key, delete it separately in OpenRouter.</p>
        <a href="https://openrouter.ai/settings/keys" target="_blank" rel="noopener noreferrer" className="text-sm underline underline-offset-4">Open OpenRouter key settings</a>
        {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
      </ModalBody>
      <ModalFooter><Button label="Cancel" variant="secondary" disabled={!!removing} onClick={() => setDeleteId(null)} /><Button label="Remove connector" variant="danger" busy={!!removing} onClick={() => void remove(deleting.id)} /></ModalFooter>
    </Modal>}
  </section>;
}

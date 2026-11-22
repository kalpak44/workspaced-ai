import { useRef, useState } from "react";
import { Plug, Trash2 } from "lucide-react";
import { isRejectedConnectorKey, useAiConnectors } from "../../hooks/useAiConnectors";
import { useModelProviders } from "../../hooks/useModelProviders";
import { useOpenRouterConnect } from "../../hooks/useOpenRouterConnect";
import { Button, IconButton, Spinner, TextField } from "../atoms";

export function AiConnectorsPanel() {
  const { connectors, add, remove: removeConnector, loading, error: loadError } = useAiConnectors();
  const { providers } = useModelProviders();
  const [name, setName] = useState("");
  const [providerId, setProviderId] = useState("openrouter");
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [removing, setRemoving] = useState<string | null>(null);
  const oauthName = useRef("");
  const save = async (apiKey: string, connectorName = name) => {
    setBusy(true); setError(""); setNotice("");
    try {
      await add(connectorName, providerId, apiKey); setKey(""); setName("");
      setNotice("AI connector saved. You can now select it in your workspaces.");
    } catch (cause) {
      setError(isRejectedConnectorKey(cause) ? "The provider rejected this API key." : "Could not save the AI connector. Please try again.");
    } finally { setBusy(false); }
  };
  const oauth = useOpenRouterConnect(apiKey => { setKey(apiKey); void save(apiKey, oauthName.current); });
  const connecting = oauth.status === "waiting" || oauth.status === "exchanging";
  const remove = async (id: string) => {
    setRemoving(id); setError("");
    try { await removeConnector(id); }
    catch { setError("Could not delete the AI connector. Please try again."); }
    finally { setRemoving(null); }
  };
  return <section aria-label="AI connectors" className="space-y-6">
    <div className="card overflow-hidden">
      <div className="border-b border-zinc-200 p-5 dark:border-zinc-800"><h2 className="font-semibold">Your AI connectors</h2><p className="mt-1 text-sm text-zinc-500">Named connections you can reuse across your workspaces. Keys are encrypted and never shown again.</p></div>
      {loading ? <div className="p-5"><Spinner label="Loading AI connectors" /></div> : <div className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {connectors.length === 0 && <p className="p-5 text-sm text-zinc-500">No connectors yet. Add your first connection below.</p>}
        {connectors.map(connector => <div key={connector.id} className="flex items-center justify-between gap-4 p-5"><div className="min-w-0"><p className="break-words font-medium">{connector.name}</p><p className="text-sm text-zinc-500">{providers.find(p => p.id === connector.provider)?.name ?? connector.provider}</p></div><IconButton label={`Delete ${connector.name}`} icon={Trash2} className="shrink-0" aria-busy={removing === connector.id} disabled={removing !== null} onClick={() => void remove(connector.id)} /></div>)}
      </div>}
      <p className="border-t border-zinc-200 px-5 py-3 text-xs text-zinc-500 dark:border-zinc-800">Deleting a connector leaves existing workspace keys in place. Select another connector in a workspace to change its key.</p>
    </div>
    <form aria-label="Add AI connector" className="card space-y-4 p-5" onSubmit={event => { event.preventDefault(); void save(key); }}>
      <h2 className="font-semibold">Add AI connector</h2>
      <TextField id="connector-name" label="Connector name" placeholder="Personal, work, or a project name" maxLength={100} required value={name} disabled={busy || connecting} onChange={event => setName(event.target.value)} />
      <label className="block text-sm font-medium">Model provider<select aria-label="Model provider" className="mt-1.5 block w-full rounded-lg border border-zinc-300 bg-transparent p-2 dark:border-zinc-700" value={providerId} disabled={busy || connecting} onChange={event => setProviderId(event.target.value)}>{providers.map(provider => <option key={provider.id} value={provider.id}>{provider.name}</option>)}</select></label>
      {providerId === "openrouter" && <div className="space-y-2"><Button label="Connect OpenRouter" icon={Plug} variant="secondary" busy={oauth.status === "exchanging"} disabled={!name.trim() || busy} onClick={() => { oauthName.current = name.trim(); oauth.connect(async () => `workspaced: ${oauthName.current}`); }} /><p className="text-xs text-zinc-500">Approve in OpenRouter to create and save a key, or paste an existing key below.</p></div>}
      {connecting && <p role="status" className="text-sm text-zinc-500">{oauth.status === "waiting" ? "Approve the key in the OpenRouter window." : "Getting your key from OpenRouter…"}</p>}
      {(oauth.status === "blocked" || oauth.status === "failed") && <p role="alert" className="text-sm text-rose-600">{oauth.status === "blocked" ? "Allow pop-ups for this site and try again." : "OpenRouter did not issue a key. Try again or paste a key."}</p>}
      <TextField id="connector-key" label="API key" type="password" autoComplete="off" maxLength={1000} value={key} disabled={busy || connecting} onChange={event => setKey(event.target.value)} />
      <Button label="Save connector" type="submit" busy={busy} disabled={!name.trim() || !key.trim() || connecting} />
    </form>
    {(error || loadError) && <p role="alert" className="text-sm text-rose-600">{error || loadError}</p>}
    {notice && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">{notice}</p>}
  </section>;
}

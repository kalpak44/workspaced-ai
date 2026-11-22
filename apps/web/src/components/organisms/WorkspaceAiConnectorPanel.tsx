import { useWorkspaceAiConnector } from "../../hooks/useWorkspaceAiConnector";
import { useAiConnectors } from "../../hooks/useAiConnectors";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button, Select } from "../atoms";

export function WorkspaceAiConnectorPanel({ workspace, onChanged }: { readonly workspace: Workspace; readonly onChanged: (changes: Partial<Workspace>) => void }) {
  const { connectors, loading, error: loadError } = useAiConnectors();
  const { selected, setSelected, current, reading, busy, error, save } = useWorkspaceAiConnector(workspace, onChanged);
  return <form aria-label="AI connector" className="space-y-3 border-b border-zinc-200 pb-5 dark:border-zinc-800" onSubmit={event => { event.preventDefault(); void save(); }}>
    <label htmlFor="workspace-ai-connector" className="block text-sm font-medium text-zinc-700 dark:text-zinc-300">
      AI connector <code className="ml-1 font-mono text-xs text-zinc-500">{workspace.modelProvider === "openrouter" ? "OPENROUTER_API_KEY" : "Provider API key"}</code>
    </label>
    <Select id="workspace-ai-connector" aria-label="Saved connector" className="w-full" value={selected} disabled={loading || reading || busy} onChange={event => setSelected(event.target.value)}>
      <option value="">{current ? "Choose a connector" : "Current workspace key"}</option>
      {connectors.filter(c => c.provider === workspace.modelProvider).map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
    </Select>
    <p className="text-xs text-zinc-500 dark:text-zinc-400">Choose a saved connector. Changes apply after a restart. <a href="/settings/ai-connectors" className="underline underline-offset-4">Manage AI connectors in General settings</a>.</p>
    <div className="flex justify-end"><Button label="Save connector" type="submit" busy={busy} disabled={!selected || selected === current || reading || loading} /></div>
    {(error || loadError) && <p role="alert" className="text-sm text-rose-600">{error || loadError}</p>}
  </form>;
}

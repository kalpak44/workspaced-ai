import { useWorkspaceAiConnector } from "../../hooks/useWorkspaceAiConnector";
import type { Workspace } from "../../hooks/useWorkspaces";
import { SettingsSection } from "../molecules";
import { Button, Select } from "../atoms";
import { ChatGptConnect } from "./ChatGptConnect";

const providerName = (provider: string) => provider === "openai" ? "OpenAI API key" : provider === "openrouter" ? "OpenRouter" : provider;
export function WorkspaceAiConnectorPanel({ workspace, onChanged }: { readonly workspace: Workspace; readonly onChanged: (changes: Partial<Workspace>) => void }) {
  const { selected, setSelected, current, status, reading, busy, error, save, refresh } = useWorkspaceAiConnector(workspace, onChanged);
  const available = [...status?.choices ?? []].sort((left, right) => Number(!!right.trial) - Number(!!left.trial));
  const changing = status?.phase === "APPLYING" || status?.phase === "ROLLING_BACK" || status?.phase === "BLOCKED";
  return <SettingsSection title="Profile" description="Choose the AI connector this workspace uses. Its credentials are managed automatically; secrets and variables cannot override them."><div className="space-y-6"><form aria-label="AI connector" className="min-w-0 space-y-3" onSubmit={event => { event.preventDefault(); void save(); }}>
    <label htmlFor="workspace-ai-connector" className="block text-sm font-medium text-zinc-700 dark:text-zinc-300">AI connector <span className="ml-1 text-xs text-zinc-500">{status?.active?.authType === "chatgpt" ? "ChatGPT sign-in" : (status?.active?.provider ?? workspace.modelProvider) === "openai" ? "OPENAI_API_KEY" : "OPENROUTER_API_KEY"}</span></label>
    <Select id="workspace-ai-connector" aria-label="Saved connector" className="w-full min-w-0" value={selected} disabled={reading || busy || changing || available.length === 0} onChange={event => setSelected(event.target.value)}>
      {available.map(c => <option key={c.id} value={c.id}>{c.name}{c.trial ? " — Default" : c.authType === "chatgpt" ? " — ChatGPT" : ""}</option>)}
    </Select>
    {status && <div className="space-y-1 break-words text-sm" aria-live="polite">
      <p aria-label="Active AI connector">Active (last confirmed): {status.active ? `${status.active.name} · ${status.active.authType === "chatgpt" ? "ChatGPT plan" : providerName(status.active.provider)}` : `Legacy ${providerName(workspace.modelProvider)} configuration`}</p>
      {status.pending && <p aria-label="Pending AI connector">Pending: {status.pending.name} · {status.pending.authType === "chatgpt" ? "ChatGPT plan" : providerName(status.pending.provider)}</p>}
      {status.phase === "STAGED" && !status.error && <p role="status">Restart required. The pending connector is not active yet.</p>}
      {status.phase === "APPLYING" && <p role="status">Restarting with the pending connector. Waiting for verified readiness.</p>}
      {status.phase === "ROLLING_BACK" && <p role="alert">Restart failed. Restoring the previous connector and waiting for readiness. Sending is blocked.</p>}
      {status.phase === "STAGED" && status.error && <p role="alert">Restart failed. The previous connector was restored; the pending choice is not active. Restart to try again.</p>}
      {status.phase === "BLOCKED" && <p role="alert">The provider change could not be verified. Sending is blocked. Restart to retry recovery.</p>}
    </div>}
    <p className="text-xs text-zinc-500 dark:text-zinc-400">{!reading && available.length === 0 ? "No supported connectors available. Create one in General settings." : "Choose a saved connector. Changes apply only after a verified restart."} <a href="/settings/ai-connectors" className="underline underline-offset-4">Manage AI connectors in General settings</a>.</p>
    <div className="flex justify-end"><Button label="Save connector" type="submit" busy={busy} disabled={!status || !selected || selected === current || reading || changing} /></div>
    {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
  </form><ChatGptConnect key={workspace.id} workspaceId={workspace.id} disabled={reading || busy || changing || workspace.status !== "READY"} onConnected={async id => { setSelected(id); await refresh(); }} /></div></SettingsSection>;
}

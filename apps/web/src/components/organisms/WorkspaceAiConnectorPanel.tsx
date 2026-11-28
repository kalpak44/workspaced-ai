import { useState } from "react";
import { KeyRound, Sparkles } from "lucide-react";
import { useWorkspaceAiConnector } from "../../hooks/useWorkspaceAiConnector";
import type { Workspace } from "../../hooks/useWorkspaces";
import { SettingsSection } from "../molecules";
import { Button, Select, ChoiceCard, Spinner } from "../atoms";
import { ChatGptSetupProgress } from "./ChatGptSetupProgress";
import { ChatGptConnect } from "./ChatGptConnect";

const providerName = (provider: string) => provider === "openai" ? "OpenAI API key" : provider === "openrouter" ? "OpenRouter" : provider;
export function WorkspaceAiConnectorPanel({ workspace, onChanged }: { readonly workspace: Workspace; readonly onChanged: (changes: Partial<Workspace>) => void }) {
  const { selected, setSelected, current, status, reading, busy, error, save, refresh } = useWorkspaceAiConnector(workspace, onChanged);
  const available = [...status?.choices ?? []].sort((left, right) => Number(!!right.trial) - Number(!!left.trial));
  const [chosenMethod, setChosenMethod] = useState<"saved" | "chatgpt" | null>(null);
  const method = chosenMethod ?? (status?.chatGptSetup || (status?.pending ?? status?.active)?.authType === "chatgpt" ? "chatgpt" : "saved");
  const choices = available.filter(c => (c.authType === "chatgpt") === (method === "chatgpt"));
  const chooseMethod = (next: "saved" | "chatgpt") => {
    setChosenMethod(next);
    const matching = available.filter(c => (c.authType === "chatgpt") === (next === "chatgpt"));
    setSelected(matching.find(c => c.id === current)?.id ?? matching[0]?.id ?? "");
  };
  const changing = status?.phase === "APPLYING" || status?.phase === "ROLLING_BACK" || status?.phase === "BLOCKED";
  return <SettingsSection title="Profile" description="Choose the AI connector this workspace uses. Its credentials are managed automatically; secrets and variables cannot override them."><div className="space-y-6">
    {reading && <div className="flex items-center gap-2 text-sm text-zinc-500"><Spinner label="Checking workspace setup" />Checking workspace setup…</div>}
    {method === "chatgpt" && (status?.chatGptSetup || (status?.active?.authType === "chatgpt" && status.phase === "NONE" && workspace.status === "READY")) && <ChatGptSetupProgress workspaceId={workspace.id} status={workspace.status} connected={!status.chatGptSetup && status.active?.authType === "chatgpt"} />}
    <fieldset className="grid gap-2 sm:grid-cols-2">
      <legend className="mb-2 text-sm font-medium">Connection method</legend>
      <ChoiceCard name="profile-connection-method" value="saved" label="Saved API connector" detail="Use a saved API key" icon={KeyRound} checked={method === "saved"} disabled={reading || busy || changing || status?.chatGptSetup} onChange={() => chooseMethod("saved")} />
      <ChoiceCard name="profile-connection-method" value="chatgpt" label="Use ChatGPT plan" detail="Sign in to ChatGPT" icon={Sparkles} checked={method === "chatgpt"} disabled={reading || busy || changing} onChange={() => chooseMethod("chatgpt")} />
    </fieldset>
    <form aria-label="AI connector" className="min-w-0 space-y-3" onSubmit={event => { event.preventDefault(); void save(); }}>
    {(method === "saved" || choices.length > 0) && <><label htmlFor="workspace-ai-connector" className="block text-sm font-medium text-zinc-700 dark:text-zinc-300">AI connector <span className="ml-1 text-xs text-zinc-500">{status?.active?.authType === "chatgpt" ? "ChatGPT sign-in" : (status?.active?.provider ?? workspace.modelProvider) === "openai" ? "OPENAI_API_KEY" : "OPENROUTER_API_KEY"}</span></label>
    <Select id="workspace-ai-connector" aria-label="Saved connector" className="w-full min-w-0" value={selected} disabled={reading || busy || changing || choices.length === 0} onChange={event => setSelected(event.target.value)}>
      {choices.map(c => <option key={c.id} value={c.id}>{c.name}{c.trial ? " — Default" : c.authType === "chatgpt" ? " — ChatGPT" : ""}</option>)}
    </Select></>}
    {status && <div className="space-y-1 break-words text-sm" aria-live="polite">
      <p aria-label="Active AI connector">Active (last confirmed): {status.active ? `${status.active.name} · ${status.active.authType === "chatgpt" ? "ChatGPT plan" : providerName(status.active.provider)}` : status.chatGptSetup ? "ChatGPT not connected yet" : `Legacy ${providerName(workspace.modelProvider)} configuration`}</p>
      {status.pending && <p aria-label="Pending AI connector">Pending: {status.pending.name} · {status.pending.authType === "chatgpt" ? "ChatGPT plan" : providerName(status.pending.provider)}</p>}
      {status.phase === "STAGED" && !status.error && <p role="status">Restart required. The pending connector is not active yet.</p>}
      {status.phase === "APPLYING" && <p role="status">Restarting with the pending connector. Waiting for verified readiness.</p>}
      {status.phase === "ROLLING_BACK" && <p role="alert">Restart failed. Restoring the previous connector and waiting for readiness. Sending is blocked.</p>}
      {status.phase === "STAGED" && status.error && <p role="alert">Restart failed. The previous connector was restored; the pending choice is not active. Restart to try again.</p>}
      {status.phase === "BLOCKED" && <p role="alert">The provider change could not be verified. Sending is blocked. Restart to retry recovery.</p>}
    </div>}
    {method === "saved" && <p className="text-xs text-zinc-500 dark:text-zinc-400">{!reading && choices.length === 0 ? "No supported connectors available. Create one in General settings." : "Choose a saved connector. Changes apply only after a verified restart."} <a href="/settings/ai-connectors" className="underline underline-offset-4">Manage AI connectors in General settings</a>.</p>}
    {(method === "saved" || choices.length > 0) && <div className="flex justify-end"><Button label="Save connector" type="submit" busy={busy} disabled={choices.length === 0 || !status || !selected || selected === current || reading || changing} /></div>}
    {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
  </form>{method === "chatgpt" && <>
    {workspace.status !== "READY" && !status?.chatGptSetup && <p role="status" className="text-sm text-zinc-500">Start the workspace and wait until it is ready to connect ChatGPT. VM costs are separate from your ChatGPT plan.</p>}
    <ChatGptConnect key={workspace.id} workspaceId={workspace.id} disabled={reading || busy || changing || workspace.status !== "READY"} onConnected={async id => { setSelected(id); return (await refresh())?.restartRequired; }} /></>}</div></SettingsSection>;
}

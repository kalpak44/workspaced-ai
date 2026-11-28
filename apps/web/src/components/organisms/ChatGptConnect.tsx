import { useState } from "react";
import { useChatGptConnect } from "../../hooks/useChatGptConnect";
import { Button, TextField } from "../atoms";
import { AiProviderNotice } from "../molecules/AiProviderNotice";

export function ChatGptConnect({ workspaceId, disabled, onConnected }: {
  readonly workspaceId: string; readonly disabled: boolean; readonly onConnected: (connectorId: string) => Promise<boolean | void>;
}) {
  const [name, setName] = useState("ChatGPT");
  const [accepted, setAccepted] = useState(false);
  const { attempt, busy, error, notice, start, cancel } = useChatGptConnect(workspaceId, onConnected);
  return <div aria-label="Connect ChatGPT" className="space-y-4 rounded-xl border border-zinc-200 p-4 dark:border-zinc-800">
    <div><h3 className="font-semibold">Use your ChatGPT plan</h3>
      <p className="mt-1 text-sm leading-6 text-zinc-500">Sign in with ChatGPT Plus or Pro. Usage counts against your plan’s limits. This connection belongs to this workspace; sign in separately in other workspaces.</p></div>
    {!attempt && <>
      <TextField id="chatgpt-profile-name" label="ChatGPT profile name" maxLength={100} value={name} disabled={disabled || busy} onChange={event => setName(event.target.value)} />
      <AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={disabled || busy} />
      <Button label="Connect ChatGPT" variant="secondary" busy={busy} disabled={disabled || !accepted || !name.trim()} onClick={() => void start(name, accepted)} />
    </>}
    {attempt && <div className="space-y-3" aria-live="polite">
      <p className="text-sm">Open ChatGPT and enter this code to approve the connection:</p>
      <p className="select-all rounded-lg bg-zinc-100 px-4 py-3 font-mono text-lg tracking-wide dark:bg-zinc-900">{attempt.instructions.replace(/^Enter code:\s*/, "")}</p>
      <div className="flex flex-wrap items-center gap-4">
        <a href={attempt.url} target="_blank" rel="noopener noreferrer" className="text-sm font-medium underline underline-offset-4">Continue to ChatGPT</a>
        <Button label="Cancel ChatGPT sign-in" variant="secondary" busy={busy} onClick={() => void cancel()} />
      </div>
      <p role="status" className="text-xs text-zinc-500">Waiting for approval. This code expires in about 10 minutes.</p>
    </div>}
    {notice && <p role="status" className="text-sm">{notice}</p>}
    {error && <p role="alert" className="text-sm text-rose-600">{error}</p>}
  </div>;
}

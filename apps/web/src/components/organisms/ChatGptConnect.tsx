import { useEffect, useRef, useState } from "react";
import { cancelChatGpt, connectChatGpt, pollChatGpt, selectAiConnector, type ChatGptAttempt } from "../../api/aiConnectorsApi";
import { ApiError } from "../../api/http";
import { Button, TextField } from "../atoms";
import { AiProviderNotice } from "../molecules/AiProviderNotice";

export function ChatGptConnect({ workspaceId, disabled, onConnected }: {
  readonly workspaceId: string; readonly disabled: boolean; readonly onConnected: (connectorId: string) => Promise<void>;
}) {
  const [name, setName] = useState("ChatGPT");
  const [accepted, setAccepted] = useState(false);
  const [attempt, setAttempt] = useState<ChatGptAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const latest = useRef(onConnected);
  latest.current = onConnected;
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);

  useEffect(() => {
    if (!attempt) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      if (Date.parse(attempt.expiresAt) <= Date.now()) {
        setAttempt(null); setError("ChatGPT sign-in expired. Please connect again."); return;
      }
      try {
        const result = await pollChatGpt(workspaceId, attempt.id);
        if (cancelled) return;
        setError("");
        if (result.status === "complete" && result.connectorId) {
          setBusy(true);
          try {
            await selectAiConnector(workspaceId, result.connectorId);
            if (cancelled) return;
            await latest.current(result.connectorId);
            if (!cancelled) setNotice("ChatGPT connected. Restart the workspace to activate this profile.");
          } catch {
            if (!cancelled) setError("ChatGPT was saved. Select it in Saved connector to apply it, then restart the workspace.");
          } finally { if (!cancelled) { setBusy(false); setAttempt(null); } }
          return;
        }
        if (result.status !== "pending") {
          setAttempt(null); setError("ChatGPT sign-in expired or was cancelled. Please connect again."); return;
        }
        timer = setTimeout(read, attempt.pollSeconds * 1000);
      } catch {
        if (cancelled) return;
        // A transient network error must not discard an authorization that may have completed.
        setError("Could not check ChatGPT sign-in. Retrying…");
        timer = setTimeout(read, attempt.pollSeconds * 1000);
      }
    };
    timer = setTimeout(read, attempt.pollSeconds * 1000);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [attempt, workspaceId]);

  const start = async () => {
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await connectChatGpt(workspaceId, name, accepted);
      if (active.current) setAttempt(result);
    } catch (cause) {
      if (active.current) setError(cause instanceof ApiError && cause.status === 409
        ? "Start the workspace and finish or cancel any pending ChatGPT sign-in before connecting again."
        : "Could not start ChatGPT sign-in. Please try again.");
    } finally { if (active.current) setBusy(false); }
  };
  const cancel = async () => {
    if (!attempt) return;
    setBusy(true); setError("");
    try { await cancelChatGpt(workspaceId, attempt.id); setAttempt(null); }
    catch { setError("Could not cancel sign-in. Please try again."); }
    finally { setBusy(false); }
  };
  return <div aria-label="Connect ChatGPT" className="space-y-4 rounded-xl border border-zinc-200 p-4 dark:border-zinc-800">
    <div><h3 className="font-semibold">Use your ChatGPT plan</h3>
      <p className="mt-1 text-sm leading-6 text-zinc-500">Sign in with ChatGPT Plus or Pro. Usage counts against your plan’s limits. This connection belongs to this workspace; sign in separately in other workspaces.</p></div>
    {!attempt && <>
      <TextField id="chatgpt-profile-name" label="ChatGPT profile name" maxLength={100} value={name} disabled={disabled || busy} onChange={event => setName(event.target.value)} />
      <AiProviderNotice accepted={accepted} onChange={setAccepted} disabled={disabled || busy} />
      <Button label="Connect ChatGPT" variant="secondary" busy={busy} disabled={disabled || !accepted || !name.trim()} onClick={() => void start()} />
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

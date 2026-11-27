import { useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { Button, TextField } from "../atoms";

export function AiCreditWarningSettings() {
  const { account, saveAiCreditPreferences } = useAccount();
  const saved = account?.aiCreditWarningUsd ?? 1;
  const [draft, setDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const value = draft ?? String(saved);
  const amount = Number(value);
  const valid = value.trim() !== "" && Number.isFinite(amount) && amount >= 0 && amount <= 100000 && /^\d+(\.\d{0,2})?$/.test(value);
  return <form aria-label="AI credit warnings" className="card mb-6 space-y-4 p-5 sm:p-6" onSubmit={async event => {
    event.preventDefault();
    if (!valid || busy) return;
    setBusy(true); setMessage("");
    try { await saveAiCreditPreferences(amount); setDraft(null); setMessage("AI credit warning threshold saved."); }
    catch { setMessage("Could not save the threshold. Please try again."); }
    finally { setBusy(false); }
  }}>
    <div><h2 className="font-semibold">AI credit warnings</h2><p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">Warn when your workspace's AI connector has less than this amount left. Applies to your view of shared workspaces too. Demo and trial banners stay hidden.</p></div>
    <div className="flex flex-wrap items-end gap-3">
      <div className="w-44"><TextField id="ai-credit-warning" label="Warning threshold (USD)" type="number" min="0" max="100000" step="0.01" value={value} onChange={event => { setDraft(event.target.value); setMessage(""); }} hint="Default $1.00. Set 0 to hide low-credit warnings." /></div>
      <Button label="Save threshold" type="submit" busy={busy} disabled={!valid || amount === saved} />
    </div>
    <p className="text-xs text-zinc-500">Sending is always disabled when the connector has no credits left.</p>
    {message && <p role="status" className="text-sm">{message}</p>}
  </form>;
}

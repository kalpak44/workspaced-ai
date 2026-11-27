import { useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { Button } from "../atoms";

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
    <div><h2 className="font-semibold">AI credit warnings</h2><p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">Warn when your workspace's AI connector has less than this amount left. Applies to your view of shared workspaces too. Trial connectors always warn below $0.20.</p></div>
    <div className="space-y-1.5">
      <label htmlFor="ai-credit-warning" className="block text-sm font-medium text-zinc-700 dark:text-zinc-300">Warning threshold (USD)</label>
      <div className="flex items-center gap-3">
        <input id="ai-credit-warning" aria-describedby="ai-credit-warning-note" className="field min-h-9 min-w-0 flex-1 px-3 py-2 text-sm sm:w-44 sm:flex-none" type="number" min="0" max="100000" step="0.01" value={value} onChange={event => { setDraft(event.target.value); setMessage(""); }} />
        <Button label="Save threshold" type="submit" busy={busy} disabled={!valid || amount === saved} />
      </div>
      <p id="ai-credit-warning-note" className="text-xs text-zinc-500 dark:text-zinc-400">Default $1.00. Set 0 to hide low-credit warnings for personal connectors.</p>
    </div>
    <p className="text-xs text-zinc-500">Sending is always disabled when the connector has no credits left.</p>
    {message && <p role="status" className="text-sm">{message}</p>}
  </form>;
}

import { useEffect, useState } from "react";
import { SESSION_TTL_OPTIONS, useSessionTtlSettings } from "../../hooks/useSessionTtlSettings";
import { Button, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";

interface Props {
  readonly workspaceId: string;
}

/** Workspace session retention settings. */
export function SessionTtlPanel({ workspaceId }: Props) {
  const { settings, loading, saving, error, retry, update } = useSessionTtlSettings(workspaceId);
  const [notice, setNotice] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [hours, setHours] = useState(168);
  useEffect(() => {
    if (settings) {
      setEnabled(settings.sessionTtlEnabled);
      setHours(settings.sessionTtlHours);
    }
  }, [settings]);
  const changed = settings !== null && (enabled !== settings.sessionTtlEnabled || hours !== settings.sessionTtlHours);

  return (
    <SettingsSection title="Session TTL" description="Choose how long inactive sessions in this workspace should be kept after your last interaction.">
      {loading ? <Spinner label="Loading session settings" className="size-5 text-zinc-400" /> : !settings ? <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div> : <>
      <div role="group" aria-label="Session TTL" className="flex gap-1 self-start rounded-md bg-zinc-100 p-0.5 text-sm dark:bg-zinc-800">
        {([true, false] as const).map((option) => (
          <button key={String(option)} type="button" disabled={saving} aria-pressed={enabled === option}
            onClick={() => { setEnabled(option); setNotice(""); }}
            className={`rounded px-3 py-1 ${enabled === option ? "bg-white shadow-sm dark:bg-zinc-950" : "text-zinc-600 dark:text-zinc-400"}`}>
            {option ? "Enabled" : "Disabled"}
          </button>
        ))}
      </div>
      <form aria-label="Session TTL settings" className="flex flex-col gap-3 rounded-md bg-zinc-50 p-4 dark:bg-zinc-900/60"
        onSubmit={async (event) => {
          event.preventDefault();
          setNotice("");
          setNotice(await update({ sessionTtlEnabled: enabled, sessionTtlHours: hours })
            ? "Session settings saved."
            : "Could not save session settings. Please try again.");
        }}>
        <div className="flex max-w-sm flex-col gap-1.5 text-sm">
          <label htmlFor="session-ttl-hours" className="font-medium text-zinc-700 dark:text-zinc-300">Delete after inactivity of</label>
          <select id="session-ttl-hours" value={hours} disabled={!enabled || saving} aria-describedby="session-ttl-hint"
            onChange={(event) => { setHours(Number(event.target.value)); setNotice(""); }}
            className="min-w-0 w-full rounded-md border border-zinc-300 bg-white px-3 py-2 outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400 dark:focus:ring-white/10">
            {SESSION_TTL_OPTIONS.map((option) => <option key={option.hours} value={option.hours}>{option.label}</option>)}
          </select>
        </div>
        <p id="session-ttl-hint" className="text-xs text-zinc-500 dark:text-zinc-400">Session updates, including model responses, restart the inactivity timer. Expired sessions and their files are permanently deleted. Cleanup runs every minute when the workspace is available.</p>
        <div className="flex justify-end">
          <Button label="Save session settings" type="submit" busy={saving} disabled={!changed} />
        </div>
      </form>
      </>}
      {notice && <p role="status" className="text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>}
    </SettingsSection>
  );
}

import { useEffect, useState } from "react";
import { useWebServer } from "../../hooks/useWebServer";
import { Button, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";
import { WebDomainsPanel } from "./WebDomainsPanel";

interface Props { readonly workspaceId: string }

/** All addresses share one nginx; each address has its own enabled state. */
export function WebServerPanel({ workspaceId }: Props) {
  const { server, loading, saving, error, retry, update } = useWebServer(workspaceId);
  const [allowed, setAllowed] = useState(false);
  const [notice, setNotice] = useState("");
  const [problem, setProblem] = useState("");
  useEffect(() => { if (server) setAllowed(server.agentAllowed); }, [server]);
  return <SettingsSection title="Web server" description="Publish sites and apps at multiple HTTPS addresses. Your workspace’s nginx controls what each hostname serves.">
    {loading ? <Spinner label="Loading the web server" className="size-5 text-zinc-400" /> : !server ? <div className="space-y-3">
      <p role="alert" className="text-sm text-rose-600">{error}</p>
      <Button label="Retry" variant="secondary" onClick={retry} />
    </div> : !server.domain ? <p className="text-sm text-zinc-500">This instance does not offer web servers.</p> : <>
      <WebDomainsPanel workspaceId={workspaceId} domain={server.domain} revision={server.name} onChange={retry} />
      <form aria-label="Agent publishing permission" className="space-y-3 rounded-md bg-zinc-50 p-4 dark:bg-zinc-900/60" onSubmit={async event => {
        event.preventDefault(); setNotice(""); setProblem("");
        const failure = await update(server.name, allowed);
        if (failure) setProblem(failure); else setNotice("Agent permission saved.");
      }}>
        <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
          <input type="checkbox" checked={allowed} disabled={saving} onChange={event => { setAllowed(event.target.checked); setNotice(""); setProblem(""); }} className="mt-0.5 size-4 accent-zinc-900 dark:accent-zinc-100" />
          <span>Let the agent manage public addresses<span className="block text-xs text-zinc-500">It can add, enable, disable and remove addresses when you ask it to publish an app.</span></span>
        </label>
        <Button label="Save agent permission" type="submit" busy={saving} disabled={allowed === server.agentAllowed} />
      </form>
      <p className="text-xs text-zinc-500">Anyone with an enabled address can open it. Your nginx config and site files are kept when you disable or remove addresses.</p>
    </>}
    {problem && <p role="alert" className="text-sm text-rose-600">{problem}</p>}
    {notice && <p role="status" className="text-sm text-zinc-500">{notice}</p>}
  </SettingsSection>;
}

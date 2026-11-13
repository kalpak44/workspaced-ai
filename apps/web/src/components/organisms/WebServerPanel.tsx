import { ExternalLink } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useWebServer, WEB_SERVER_NAME } from "../../hooks/useWebServer";
import { Button, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";

interface Props {
  readonly workspaceId: string;
}

/** The workspace's nginx, and the public name it answers under. */
export function WebServerPanel({ workspaceId }: Props) {
  const { server, loading, saving, error, retry, update } = useWebServer(workspaceId);
  const [enabled, setEnabled] = useState(false);
  const [name, setName] = useState("");
  const [notice, setNotice] = useState("");
  const [problem, setProblem] = useState("");
  const [agentAllowed, setAgentAllowed] = useState(false);
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (server) {
      setEnabled(Boolean(server.name));
      setName(server.name ?? "");
      setAgentAllowed(server.agentAllowed);
    }
  }, [server]);
  const invalid = name !== "" && !WEB_SERVER_NAME.test(name);
  const serverChanged = server !== null && (enabled ? name !== (server.name ?? "") : server.name !== null);
  const changed = serverChanged || (server !== null && agentAllowed !== server.agentAllowed);
  const ready = changed && (!serverChanged || !enabled || (name !== "" && !invalid));

  function edited() {
    setNotice("");
    setProblem("");
  }

  return (
    <SettingsSection title="Web server" description="Serve a site or your own app from this workspace at a public address. nginx starts with the workspace, and the agent can configure it for you.">
      {loading ? <Spinner label="Loading the web server" className="size-5 text-zinc-400" /> : !server ? <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div> : !server.domain ? (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">This instance does not offer web servers.</p>
      ) : <>
      {server.url && (
        <p className="min-w-0 text-sm text-zinc-700 dark:text-zinc-300">
          Public at{" "}
          <a href={server.url} target="_blank" rel="noreferrer"
            className="inline-flex max-w-full items-center gap-1 break-all font-medium text-zinc-900 underline underline-offset-2 hover:text-zinc-600 dark:text-zinc-100 dark:hover:text-zinc-300">
            {server.url}<ExternalLink aria-hidden className="size-3.5 shrink-0" />
          </a>
        </p>
      )}
      <div role="group" aria-label="Web server" className="flex gap-1 self-start rounded-md bg-zinc-100 p-0.5 text-sm dark:bg-zinc-800">
        {([true, false] as const).map((option) => (
          <button key={String(option)} type="button" disabled={saving} aria-pressed={enabled === option}
            onClick={() => { setEnabled(option); edited(); }}
            className={`rounded px-3 py-1 ${enabled === option ? "bg-white shadow-sm dark:bg-zinc-950" : "text-zinc-600 dark:text-zinc-400"}`}>
            {option ? "Enabled" : "Disabled"}
          </button>
        ))}
      </div>
      <form aria-label="Web server settings" className="flex flex-col gap-3 rounded-md bg-zinc-50 p-4 dark:bg-zinc-900/60"
        onSubmit={async (event) => {
          event.preventDefault();
          edited();
          const failure = await update(enabled ? name : null, agentAllowed);
          if (failure) setProblem(failure);
          else setNotice(serverChanged && !enabled ? "Web server turned off." : "Web server saved.");
        }}>
        <div className="flex max-w-xl min-w-0 flex-col gap-1.5 text-sm">
          <label htmlFor="web-server-name" className="font-medium text-zinc-700 dark:text-zinc-300">Name</label>
          {/* Shaped like the address it makes, so it is plain which part is the user's. */}
          <div onClick={() => field.current?.focus()} className={`flex min-w-0 cursor-text items-center rounded-md border bg-white font-mono focus-within:ring-2 dark:bg-zinc-900 ${
            invalid
              ? "border-rose-500 focus-within:ring-rose-500/20"
              : "border-zinc-300 focus-within:border-zinc-500 focus-within:ring-zinc-900/10 dark:border-zinc-700 dark:focus-within:border-zinc-400 dark:focus-within:ring-white/10"
          } ${!enabled || saving ? "opacity-60" : ""}`}>
            <span aria-hidden className="shrink-0 pl-3 text-zinc-400 dark:text-zinc-500"><span className="hidden sm:inline">https://</span>box-</span>
            <input id="web-server-name" ref={field} value={name} disabled={!enabled || saving}
              placeholder="my-app" autoComplete="off" spellCheck={false} maxLength={59}
              aria-invalid={invalid || undefined} aria-describedby="web-server-name-note"
              onChange={(event) => { setName(event.target.value.toLowerCase().trim()); edited(); }}
              // As wide as the name, so the domain follows it; on a phone it takes the row instead.
              style={{ width: `${Math.max(name.length, 6) + 0.25}ch` }}
              className="min-w-0 flex-1 bg-transparent py-2 pr-3 font-medium text-zinc-900 outline-none placeholder:font-normal placeholder:text-zinc-400 sm:max-w-full sm:flex-none sm:pr-0 dark:text-zinc-100" />
            <span aria-hidden className="hidden min-w-0 truncate pr-3 text-zinc-400 sm:inline dark:text-zinc-500">.{server.domain}/</span>
          </div>
          <span id="web-server-name-note" className={invalid ? "text-rose-600 dark:text-rose-400" : "sr-only"}>
            {invalid
              ? "Use up to 59 lowercase letters, digits and hyphens, not at the start or end."
              : `Public at https://box-${name || "name"}.${server.domain}/`}
          </span>
          {/* A phone has no room for the domain inside the field. */}
          {!invalid && <span aria-hidden className="break-all font-mono text-xs text-zinc-500 sm:hidden dark:text-zinc-400">https://box-{name || "name"}.{server.domain}/</span>}
        </div>
        <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
          <input type="checkbox" checked={agentAllowed} disabled={saving}
            onChange={(event) => { setAgentAllowed(event.target.checked); edited(); }}
            className="mt-0.5 size-4 shrink-0 accent-zinc-900 dark:accent-zinc-100" />
          <span>
            Let the agent turn it on, off and rename it
            <span className="block text-xs text-zinc-500 dark:text-zinc-400">It can then make what it builds public without asking you here first.</span>
          </span>
        </label>
        <p className="text-xs text-zinc-500 dark:text-zinc-400">Anyone with the address can open it. Turning it off frees the name for other workspaces; the nginx config in ~/.config/workspaced/web and the site in ~/web/public are kept.</p>
        <div className="flex justify-end">
          <Button label="Save web server" type="submit" busy={saving} disabled={!ready} />
        </div>
      </form>
      </>}
      {problem && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{problem}</p>}
      {notice && <p role="status" className="text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>}
    </SettingsSection>
  );
}

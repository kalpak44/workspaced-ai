import { Plus, Trash2, X } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { type AddServerResult, MCP_NAME, type McpServer } from "../../hooks/useMcpServers";
import { Button, IconButton, Spinner, TextField } from "../atoms";
import { ErrorBanner, SettingsSection } from "../molecules";

const STATUS: Record<string, { label: string; tone: string }> = {
  connected: { label: "Connected", tone: "bg-emerald-500" },
  failed: { label: "Failed", tone: "bg-rose-500" },
  needs_auth: { label: "Needs sign-in", tone: "bg-amber-500" },
  needs_client_registration: { label: "Needs sign-in", tone: "bg-amber-500" },
  disabled: { label: "Disabled", tone: "bg-zinc-400" },
  pending: { label: "Connecting", tone: "bg-sky-500" },
  unknown: { label: "Workspace not running", tone: "bg-zinc-400" },
};

function StatusBadge({ status }: { readonly status: string }) {
  const { label, tone } = STATUS[status] ?? { label: status, tone: "bg-zinc-400" };
  return (
    <span className="flex shrink-0 items-center gap-1.5 text-xs text-zinc-600 dark:text-zinc-400">
      <span aria-hidden className={`size-2 rounded-full ${tone}`} />
      {label}
    </span>
  );
}

interface Props {
  readonly servers: readonly McpServer[];
  readonly loading: boolean;
  readonly removing: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onAdd: (server: {
    name: string;
    url: string;
    headers: Record<string, string>;
  }) => Promise<AddServerResult>;
  readonly onRemove: (name: string) => void;
}

interface Header {
  readonly key: number;
  readonly name: string;
  readonly value: string;
}

const MESSAGES: Record<Exclude<AddServerResult, "added">, string> = {
  conflict: "The workspace already has a server of that name",
  invalid: "Check the URL and header names",
  failed: "Could not add the server",
};

/** Remote MCP servers: what the agent can reach, and a form to add one. */
export function McpPanel({ servers, loading, removing, error, onDismissError, onAdd, onRemove }: Props) {
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [headers, setHeaders] = useState<Header[]>([]);
  const [nextKey, setNextKey] = useState(0);
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const setHeader = (key: number, change: Partial<Header>) =>
    setHeaders((current) => current.map((h) => (h.key === key ? { ...h, ...change } : h)));

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (!MCP_NAME.test(name)) {
      setFormError("Use lowercase letters, digits, - and _");
      return;
    }
    if (!/^https?:\/\/\S+$/.test(url)) {
      setFormError("The URL must start with http:// or https://");
      return;
    }
    setPending(true);
    try {
      const result = await onAdd({
        name,
        url,
        headers: Object.fromEntries(
          headers.filter((h) => h.name.trim()).map((h) => [h.name.trim(), h.value]),
        ),
      }).catch((): AddServerResult => "failed");
      if (result === "added") {
        setName("");
        setUrl("");
        setHeaders([]);
        setFormError(null);
      } else {
        setFormError(MESSAGES[result]);
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <SettingsSection
      title="MCP servers"
      description="Tools available through OpenCode. Changes apply to every session until the workspace restarts."
    >
      {error && <ErrorBanner message={error} onDismiss={onDismissError} />}
      {loading && <Spinner label="Loading MCP servers" className="size-5 text-zinc-400" />}
      {!loading && servers.length > 0 && (
        <ul className="divide-y divide-zinc-200 rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {servers.map((server) => (
            <li key={server.name} className="flex items-center gap-3 px-3 py-2.5">
              <span className="min-w-0 flex-1">
                <span className="block font-mono text-sm">{server.name}</span>
              </span>
              <StatusBadge status={server.status} />
              {removing.has(server.name) ? (
                <Spinner label={`Removing ${server.name}`} className="size-4 text-zinc-400" />
              ) : (
                <IconButton
                  label={`Remove ${server.name}`}
                  icon={Trash2}
                  onClick={() => onRemove(server.name)}
                />
              )}
            </li>
          ))}
        </ul>
      )}
      <form
        onSubmit={submit}
        aria-label="Add an MCP server"
        className="flex flex-col gap-3 rounded-md bg-zinc-50 p-4 dark:bg-zinc-900/60"
      >
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
          <TextField
            id="mcp-name"
            label="Name"
            placeholder="github"
            autoComplete="off"
            spellCheck={false}
            value={name}
            maxLength={64}
            disabled={pending}
            error={formError}
            onChange={(e) => {
              setName(e.currentTarget.value);
              setFormError(null);
            }}
          />
          <TextField
            id="mcp-url"
            label="URL"
            type="url"
            placeholder="https://example.com/mcp"
            value={url}
            maxLength={2000}
            disabled={pending}
            onChange={(e) => {
              setUrl(e.currentTarget.value);
              setFormError(null);
            }}
          />
        </div>
        {headers.map((header, index) => (
          <div key={header.key} className="flex items-end gap-2">
            <div className="flex-1">
              <TextField
                id={`mcp-header-name-${header.key}`}
                label={`Header ${index + 1} name`}
                placeholder="Authorization"
                value={header.name}
                disabled={pending}
                onChange={(e) => setHeader(header.key, { name: e.currentTarget.value })}
              />
            </div>
            <div className="flex-[2]">
              <TextField
                id={`mcp-header-value-${header.key}`}
                label={`Header ${index + 1} value`}
                placeholder="Bearer {env:GITHUB_TOKEN}"
                value={header.value}
                disabled={pending}
                onChange={(e) => setHeader(header.key, { value: e.currentTarget.value })}
              />
            </div>
            <IconButton
              label={`Remove header ${index + 1}`}
              icon={X}
              className="mb-1.5"
              onClick={() => setHeaders((current) => current.filter((h) => h.key !== header.key))}
            />
          </div>
        ))}
        <p className="text-xs text-zinc-500 dark:text-zinc-400">
          Put tokens in Secrets below and reference them as{" "}
          <code className="rounded bg-zinc-200 px-1 font-mono dark:bg-zinc-800">{"{env:NAME}"}</code>,
          so they never leave the workspace.
        </p>
        <div className="flex justify-between gap-2">
          <Button
            label="Add header"
            variant="secondary"
            icon={Plus}
            disabled={pending}
            onClick={() => {
              setHeaders((current) => [...current, { key: nextKey, name: "", value: "" }]);
              setNextKey((key) => key + 1);
            }}
          />
          <Button label="Add server" type="submit" icon={Plus} busy={pending} disabled={!name || !url} />
        </div>
      </form>
    </SettingsSection>
  );
}

import { Copy, Plus, Trash2 } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { type FirewallProtocol, type FirewallRule, useFirewall } from "../../hooks/useFirewall";
import { Button, IconButton, Select, Spinner, TextField } from "../atoms";
import { SettingsSection } from "../molecules";

interface Props { readonly workspaceId: string }

const ANYONE = ["0.0.0.0/0", "::/0"];

function sources(rule: FirewallRule): string {
  if (rule.sourceIps.length === 0 || rule.sourceIps.every((s) => ANYONE.includes(s))) return "Anyone";
  return rule.sourceIps.join(", ");
}

/**
 * Which ports of the workspace's VM the internet may reach. A new VM has none open; whatever the
 * workspace publishes on an opened port answers at its address directly.
 */
export function FirewallPanel({ workspaceId }: Props) {
  const { firewall, loading, saving, error, saveRules, saveAgentAllowed, retry } = useFirewall(workspaceId);
  const [protocol, setProtocol] = useState<FirewallProtocol>("tcp");
  const [port, setPort] = useState("");
  const [source, setSource] = useState("");
  const [description, setDescription] = useState("");
  // null until the user ticks the box, so it shows what is saved without a render in between.
  const [choice, setAllowed] = useState<boolean | null>(null);
  const [problem, setProblem] = useState("");
  const [notice, setNotice] = useState("");
  const allowed = choice ?? firewall?.agentAllowed ?? false;

  const report = (failure: string | null, done: string) => {
    setProblem(failure ?? "");
    setNotice(failure ? "" : done);
  };

  const add = async (event: SubmitEvent) => {
    event.preventDefault();
    if (!firewall) return;
    const rule: FirewallRule = {
      protocol,
      port: protocol === "icmp" ? null : port.trim(),
      sourceIps: source.trim() ? source.split(",").map((s) => s.trim()).filter(Boolean) : [],
      description: description.trim() || null,
    };
    const failure = await saveRules([...firewall.rules, rule]);
    report(failure, "Rule added.");
    if (!failure) { setPort(""); setSource(""); setDescription(""); }
  };

  const remove = async (index: number) => {
    if (!firewall) return;
    report(await saveRules(firewall.rules.filter((_, i) => i !== index)), "Rule removed.");
  };

  return <SettingsSection title="Firewall" description="Open ports on the workspace's VM. Nothing reaches it until a rule lets it in.">
    {loading ? <Spinner label="Loading the firewall" className="size-5 text-zinc-400" /> : !firewall ? <div className="space-y-3">
      <p role="alert" className="text-sm text-rose-600">{error}</p>
      <Button label="Retry" variant="secondary" onClick={retry} />
    </div> : <>
      <div className="flex items-center gap-2 rounded-md bg-zinc-50 px-4 py-3 text-sm dark:bg-zinc-900/60">
        <span className="text-zinc-500">Address</span>
        <code className="font-mono">{firewall.address ?? "Not assigned yet"}</code>
        {firewall.address && <IconButton label="Copy the address" icon={Copy}
          onClick={() => { void navigator.clipboard?.writeText(firewall.address ?? ""); setNotice("Address copied."); }} />}
      </div>

      {firewall.rules.length === 0
        ? <p className="text-sm text-zinc-500">No ports are open.</p>
        : <ul aria-label="Firewall rules" className="divide-y divide-zinc-200 rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {firewall.rules.map((rule, index) => {
            const name = `${rule.protocol.toUpperCase()}${rule.port ? ` ${rule.port}` : ""}`;
            return <li key={`${rule.protocol}-${rule.port}-${index}`} className="flex items-center gap-3 px-4 py-2 text-sm">
              <span className="font-mono">{name}</span>
              <span className="min-w-0 flex-1 truncate text-zinc-500">{sources(rule)}{rule.description ? ` · ${rule.description}` : ""}</span>
              <IconButton label={`Remove ${name}`} icon={Trash2} disabled={saving} onClick={() => void remove(index)} />
            </li>;
          })}
        </ul>}

      <form aria-label="Open a port" onSubmit={add} className="grid gap-3 rounded-md bg-zinc-50 p-4 sm:grid-cols-[auto_1fr] dark:bg-zinc-900/60">
        <div className="flex flex-col gap-1.5 text-sm">
          <label htmlFor="firewall-protocol" className="font-medium text-zinc-700 dark:text-zinc-300">Protocol</label>
          <Select id="firewall-protocol" value={protocol} disabled={saving}
            onChange={(e) => setProtocol(e.currentTarget.value as FirewallProtocol)}>
            <option value="tcp">TCP</option>
            <option value="udp">UDP</option>
            <option value="icmp">ICMP</option>
          </Select>
        </div>
        <TextField id="firewall-port" label="Port" placeholder={protocol === "icmp" ? "No port for ICMP" : "8080 or 8000-8100"}
          value={protocol === "icmp" ? "" : port} disabled={saving || protocol === "icmp"} required={protocol !== "icmp"}
          onChange={(e) => setPort(e.currentTarget.value)} />
        <TextField id="firewall-source" label="From" placeholder="Anyone" hint="Addresses or networks, comma-separated"
          value={source} disabled={saving} onChange={(e) => setSource(e.currentTarget.value)} />
        <TextField id="firewall-description" label="Note" placeholder="Optional" maxLength={255}
          value={description} disabled={saving} onChange={(e) => setDescription(e.currentTarget.value)} />
        <div className="sm:col-span-2"><Button label="Open port" icon={Plus} type="submit" busy={saving} /></div>
      </form>

      <form aria-label="Agent firewall permission" className="space-y-3 rounded-md bg-zinc-50 p-4 dark:bg-zinc-900/60" onSubmit={async (event) => {
        event.preventDefault();
        const failure = await saveAgentAllowed(allowed);
        report(failure, "Agent permission saved.");
        if (!failure) setAllowed(null);
      }}>
        <label className="flex items-start gap-2 text-sm text-zinc-700 dark:text-zinc-300">
          <input type="checkbox" checked={allowed} disabled={saving} onChange={(event) => { setAllowed(event.target.checked); setNotice(""); setProblem(""); }} className="mt-0.5 size-4 accent-zinc-900 dark:accent-zinc-100" />
          <span>Let the agent open and close ports<span className="block text-xs text-zinc-500">It asks you in the chat before it opens one.</span></span>
        </label>
        <Button label="Save agent permission" type="submit" busy={saving} disabled={allowed === firewall.agentAllowed} />
      </form>
      <p className="text-xs text-zinc-500">Anyone a rule lets in can reach whatever listens on that port.</p>
    </>}
    {problem && <p role="alert" className="text-sm text-rose-600">{problem}</p>}
    {notice && <p role="status" className="text-sm text-zinc-500">{notice}</p>}
  </SettingsSection>;
}

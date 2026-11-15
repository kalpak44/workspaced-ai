import { Plus, SquareTerminal, Trash2 } from "lucide-react";
import type { TerminalSession } from "../../hooks/useTerminals";
import { Button, IconButton, Spinner } from "../atoms";
import { useState } from "react";

interface Props {
  readonly terminals: readonly TerminalSession[];
  readonly selectedId?: string;
  readonly loading: boolean;
  readonly creating: boolean;
  readonly disabled: boolean;
  readonly removing: string | null;
  readonly onCreate: () => void;
  readonly onSelect: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

export function TerminalSidebar({ terminals, selectedId, loading, creating, disabled, removing, onCreate, onSelect, onDelete }: Props) {
  const [query, setQuery] = useState("");
  const filtered = terminals.filter((terminal) => `${terminal.title} ${terminal.cwd}`.toLowerCase().includes(query.toLowerCase()));
  return <>
    <nav aria-label="Terminal sessions" className="flex min-h-0 flex-col px-4 sm:px-0">
      <h2 className="mb-3 text-xs font-medium text-zinc-500 dark:text-zinc-400">Terminals <span className="ml-1 text-zinc-400">{terminals.length}</span></h2>
      <input type="search" aria-label="Search terminals" placeholder="Search terminals…" value={query} onChange={(event) => setQuery(event.target.value)} className="field mb-3 w-full shrink-0 px-2.5 py-2 text-sm" />
      {loading ? <p className="px-2 text-xs text-zinc-500">Loading terminals…</p> : <ul className="flex min-h-0 gap-1 overflow-x-auto sm:block sm:space-y-1 sm:overflow-y-auto">
        {filtered.map((terminal) => <li key={terminal.id} className={`group relative flex min-w-0 max-w-64 shrink-0 items-center sm:max-w-none sm:shrink rounded-md ${terminal.id === selectedId ? "bg-zinc-100 dark:bg-zinc-800" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"}`}>
          <button type="button" disabled={removing === terminal.id} aria-label={`${terminal.title} ${terminal.status === "running" ? "Running" : `Exited${terminal.exitCode !== null ? ` · ${terminal.exitCode}` : ""}`}`} onClick={() => onSelect(terminal.id)} aria-current={terminal.id === selectedId ? "page" : undefined} className="flex min-w-0 flex-1 items-start gap-2 px-2 py-2.5 text-left disabled:opacity-50">
            <SquareTerminal aria-hidden className="mt-0.5 size-4 shrink-0 text-zinc-500" />
            <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{terminal.title}</span><span className="mt-1 flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400"><span className={`size-1.5 rounded-full ${terminal.status === "running" ? "bg-emerald-500" : "bg-zinc-400"}`} />{terminal.status === "running" ? "Running" : `Exited${terminal.exitCode !== null ? ` · ${terminal.exitCode}` : ""}`}</span></span>
          </button>
          {removing === terminal.id ? <Spinner label={`Deleting ${terminal.title}`} className="mr-2 size-3.5 shrink-0 text-zinc-400" /> : <IconButton label={`Delete terminal: ${terminal.title}`} title="Delete terminal" icon={Trash2} disabled={disabled || creating} onClick={() => onDelete(terminal.id)} className="mr-1 shrink-0 p-1 opacity-100 hover:text-rose-600 focus:opacity-100 sm:opacity-0 sm:group-hover:opacity-100" />}
        </li>)}
      </ul>}
      {!loading && filtered.length === 0 && <p className="py-3 text-xs text-zinc-500 dark:text-zinc-400">{query ? "No matching terminals." : "No terminals yet."}</p>}
    </nav>
    <div className="shrink-0 px-4 pt-3 sm:px-0 [&>button]:w-full"><Button label="New terminal" variant="secondary" icon={Plus} busy={creating} disabled={disabled} onClick={onCreate} /></div>
  </>;
}

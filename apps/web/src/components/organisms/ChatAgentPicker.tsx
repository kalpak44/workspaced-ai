import { Bot, Check, ChevronDown } from "lucide-react";
import { useState } from "react";
import type { useSessionAgents } from "../../hooks/useSessionAgents";
import { Button, Spinner } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly agents: ReturnType<typeof useSessionAgents>;
  readonly disabled?: boolean;
}

/** Session-scoped agent choice, styled like the adjacent model picker. */
export function ChatAgentPicker({ agents, disabled = false }: Props) {
  const [open, setOpen] = useState(false);
  const { selection, loading, saving, error } = agents;
  const current = selection?.agents.find((agent) => agent.id === selection.current);
  const name = current?.name || selection?.current || (loading ? "Loading agent…" : selection ? "Default agent" : "Choose agent");

  return <>
    <button
      type="button"
      aria-label={`Agent: ${name}`}
      aria-haspopup="dialog"
      aria-expanded={open}
      title={`Choose an agent · ${name}`}
      disabled={disabled || saving !== null}
      onClick={() => { setOpen(true); void agents.reload(); }}
      className="flex min-w-0 items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-normal text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-400 disabled:cursor-not-allowed disabled:opacity-40 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
    >
      {saving ? <Spinner className="size-3.5 shrink-0" /> : <Bot aria-hidden className="size-3.5 shrink-0 text-zinc-400" />}
      <span className="truncate">{name}</span>
      <ChevronDown aria-hidden className="size-3 shrink-0 text-zinc-400" />
    </button>
    {open && <Modal title="Choose an agent" description="Changes apply to subsequent turns. Your conversation and selected model are preserved." onClose={() => setOpen(false)}>
      <ModalBody>
        {error && <div role="alert" className="space-y-3 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"><p>{error}</p><Button label="Reload agents" variant="secondary" onClick={() => { void agents.reload(); }} /></div>}
        {loading ? <div className="flex justify-center py-6"><Spinner label="Loading agents" className="size-5 text-zinc-400" /></div> : <>
          {selection?.current && !current && <p className="text-sm text-zinc-500">Current agent: {selection.current}</p>}
          {selection && selection.agents.length === 0 ? <p className="text-sm text-zinc-500">No primary agents are available in this session.</p> :
            <ul role="radiogroup" aria-label="Session agent" className="max-h-80 divide-y divide-zinc-200 overflow-y-auto rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {selection?.agents.map((agent) => <li key={agent.id}>
                <button type="button" role="radio" aria-checked={selection.current === agent.id} disabled={disabled || saving !== null} onClick={() => { void agents.choose(agent.id); }} className={`flex w-full items-center gap-3 px-3 py-3 text-left disabled:cursor-not-allowed disabled:opacity-50 ${selection.current === agent.id ? "bg-zinc-100 dark:bg-zinc-800" : "hover:bg-zinc-50 dark:hover:bg-zinc-800/50"}`}>
                  <span className="min-w-0 flex-1"><span className="block text-sm font-medium">{agent.name || agent.id}</span>{agent.description && <span className="mt-1 block text-xs text-zinc-500 dark:text-zinc-400">{agent.description}</span>}</span>
                  {saving === agent.id ? <Spinner label={`Switching to ${agent.name || agent.id}`} className="size-4 shrink-0 text-zinc-400" /> : selection.current === agent.id ? <Check aria-hidden className="size-4 shrink-0" /> : null}
                </button>
              </li>)}
            </ul>}
        </>}
      </ModalBody>
      <ModalFooter><Button label="Done" variant="secondary" onClick={() => setOpen(false)} /></ModalFooter>
    </Modal>}
  </>;
}

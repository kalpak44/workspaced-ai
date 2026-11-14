import { Check, Layers, type LucideIcon, Search } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter, WorkspaceStatusIcon } from "../molecules";

interface Note {
  readonly label: string;
  readonly placeholder: string;
  readonly required?: boolean;
}

interface Props {
  readonly title: string;
  readonly description: string;
  readonly workspaces: readonly Workspace[];
  readonly actionLabel: string;
  readonly actionIcon: LucideIcon;
  /** A free-text field under the list, such as what the agent should write. */
  readonly note?: Note;
  /** False keeps the dialog open, for a failure the caller has not reported elsewhere. */
  readonly onConfirm: (workspaceId: string, note: string) => Promise<boolean>;
  readonly onClose: () => void;
}

const SEARCH_FROM = 6;

/**
 * Chooses the workspace a recipe goes to. Only a running workspace can be chosen, since the agent
 * there does the work; the others are listed so the user sees why theirs is missing.
 */
export function WorkspacePickerDialog({ title, description, workspaces, actionLabel, actionIcon, note, onConfirm, onClose }: Props) {
  const firstReady = workspaces.find((workspace) => workspace.status === "READY");
  const [selected, setSelected] = useState<string | null>(firstReady?.id ?? null);
  const [query, setQuery] = useState("");
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const noteId = useId();
  const shown = workspaces.filter((workspace) => workspace.name.toLowerCase().includes(query.trim().toLowerCase()));
  const ready = selected !== null && (!note?.required || text.trim() !== "");

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!ready || selected === null) return;
    setBusy(true);
    setFailed(false);
    const done = await onConfirm(selected, text);
    setBusy(false);
    if (!done) setFailed(true);
  };

  return (
    <Modal title={title} description={description} onClose={onClose}>
      <form aria-label={title} onSubmit={(event) => void submit(event)} className="flex min-h-0 flex-col">
        <ModalBody>
          {workspaces.length === 0 ? (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">You have no workspaces yet. Create one first.</p>
          ) : (
            <fieldset className="flex min-w-0 flex-col gap-2">
              <legend className="mb-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">Workspace</legend>
              {workspaces.length >= SEARCH_FROM && (
                <label className="mb-1 flex items-center gap-2 rounded-md border border-zinc-300 px-3 dark:border-zinc-700">
                  <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
                  <input type="search" aria-label="Search workspaces" placeholder="Search workspaces…" value={query} onChange={(event) => setQuery(event.target.value)} className="min-h-10 w-full min-w-0 bg-transparent text-sm outline-none" />
                </label>
              )}
              <ul className="flex max-h-64 flex-col gap-2 overflow-y-auto">
                {shown.map((workspace) => {
                  const available = workspace.status === "READY";
                  const checked = workspace.id === selected;
                  return (
                    <li key={workspace.id}>
                      <label className="flex cursor-pointer items-center gap-3 rounded-lg border border-zinc-200 px-4 py-3 hover:border-zinc-400 has-checked:border-zinc-900 has-checked:bg-zinc-50 has-focus-visible:ring-2 has-focus-visible:ring-zinc-400 has-disabled:cursor-not-allowed has-disabled:opacity-60 dark:border-zinc-700 dark:hover:border-zinc-500 dark:has-checked:border-zinc-200 dark:has-checked:bg-zinc-800/60">
                        <input type="radio" name="workspace" value={workspace.id} checked={checked} disabled={!available} onChange={() => setSelected(workspace.id)} className="sr-only" />
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200">
                          <Layers aria-hidden className="size-4" />
                        </span>
                        <span className="flex min-w-0 flex-1 flex-col">
                          <span className="truncate text-sm font-medium">{workspace.name}</span>
                          <span className="text-xs text-zinc-500 dark:text-zinc-400">
                            {available ? "Running" : workspace.status === "FAILED" ? "Not running" : "Starting…"}
                          </span>
                        </span>
                        <WorkspaceStatusIcon status={workspace.status} />
                        <span aria-hidden className={`flex size-5 shrink-0 items-center justify-center rounded-full border ${checked ? "border-zinc-900 bg-zinc-900 text-white dark:border-white dark:bg-white dark:text-zinc-900" : "border-zinc-300 dark:border-zinc-600"}`}>
                          {checked && <Check className="size-3" strokeWidth={3} />}
                        </span>
                      </label>
                    </li>
                  );
                })}
                {shown.length === 0 && <li className="px-1 py-2 text-sm text-zinc-500">No matching workspaces.</li>}
              </ul>
            </fieldset>
          )}
          {note && (
            <div className="flex flex-col gap-1.5 text-sm">
              <label htmlFor={noteId} className="font-medium text-zinc-700 dark:text-zinc-300">{note.label}</label>
              <textarea
                id={noteId}
                rows={3}
                value={text}
                placeholder={note.placeholder}
                onChange={(event) => setText(event.target.value)}
                className="w-full resize-y rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none placeholder:text-zinc-400 focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 dark:border-zinc-700 dark:focus:border-zinc-400 dark:focus:ring-white/10"
              />
            </div>
          )}
          {failed && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">Could not start a session there. Is the workspace running?</p>}
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={actionLabel} icon={actionIcon} type="submit" disabled={!ready} busy={busy} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

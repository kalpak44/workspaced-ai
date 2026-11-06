import { FileText, Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { INSTRUCTION_NAME, useInstructions } from "../../hooks/useInstructions";
import { INSTRUCTIONS_EXAMPLE } from "../../lib/instructionExamples";
import { Button, Spinner, TextField } from "../atoms";
import { SettingsSection } from "../molecules";
// Imported directly, not through the index: the editor is large and only loaded with this panel.
import { InstructionsEditor } from "../molecules/InstructionsEditor";

interface Props {
  readonly workspaceId: string;
}

/**
 * The workspace's instruction files: Markdown its agent is given in every session, which it can
 * also update itself. Edits are kept per file until saved, so switching between files loses
 * nothing.
 */
export function InstructionsPanel({ workspaceId }: Props) {
  const { files, stopped, error, loading, retry, save, remove } = useInstructions(workspaceId);
  const [selected, setSelected] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [newName, setNewName] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");

  // The first file is open once they have loaded, and another once the open one is gone.
  useEffect(() => {
    if (files && (selected === null || !files.some((file) => file.name === selected))) {
      setSelected(files[0]?.name ?? null);
    }
  }, [files, selected]);

  if (loading) {
    return <Spinner label="Loading instructions" className="size-5 text-zinc-400" />;
  }
  if (stopped) {
    return (
      <SettingsSection title="Instructions" description="Markdown files the agent is given in every session of this workspace.">
        <p className="text-sm text-zinc-500 dark:text-zinc-400">The instructions live in the workspace. Start it to see and edit them.</p>
      </SettingsSection>
    );
  }
  if (!files) {
    return (
      <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div>
    );
  }

  const file = files.find((candidate) => candidate.name === selected) ?? null;
  const draft = file ? (drafts[file.name] ?? file.content) : "";
  const nameError =
    newName === null || newName === "" || INSTRUCTION_NAME.test(newName)
      ? files.some((candidate) => candidate.name === newName) ? "There are instructions with this name already." : null
      : "Use up to 63 letters, digits, - and _, starting with a letter or digit.";

  async function create() {
    if (!newName || nameError) return;
    setBusy(true);
    if (await save(newName, "")) {
      setSelected(newName);
      setNewName(null);
      setNotice("Created. New chats are given it; chats already open, once the workspace restarts.");
    } else {
      setNotice("Could not create the instructions. Please try again.");
    }
    setBusy(false);
  }

  async function submit() {
    if (!file) return;
    setBusy(true);
    const saved = await save(file.name, draft);
    if (saved) {
      setDrafts(({ [file.name]: _, ...rest }) => rest);
      setNotice("Saved. The agent uses them from its next message.");
    } else {
      setNotice("Could not save the instructions. Please try again.");
    }
    setBusy(false);
  }

  async function deleteSelected() {
    if (!file) return;
    setBusy(true);
    if (await remove(file.name)) {
      setDrafts(({ [file.name]: _, ...rest }) => rest);
      setNotice(`Deleted ${file.name}.`);
    } else {
      setNotice("Could not delete the instructions. Please try again.");
    }
    setConfirmDelete(false);
    setBusy(false);
  }

  return (
    <SettingsSection
      title="Instructions"
      description="Markdown files the agent is given in every session of this workspace, one topic per file. The agent can update them too, when you ask it to remember something."
    >
      <div className="flex flex-col gap-4 md:flex-row">
        <nav aria-label="Instruction files" className="flex shrink-0 flex-col gap-1 md:w-56">
          {files.map(({ name }) => (
            <button key={name} type="button" aria-current={name === selected ? "page" : undefined}
              onClick={() => { setSelected(name); setConfirmDelete(false); setNotice(""); }}
              className={`flex min-w-0 items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium ${name === selected ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}>
              <FileText aria-hidden className="size-4 shrink-0" />
              <span className="truncate">{name}</span>
              {drafts[name] !== undefined && <span className="ml-auto text-xs text-zinc-400" aria-label="Unsaved changes">•</span>}
            </button>
          ))}
          {newName === null ? (
            <button type="button" onClick={() => { setNewName(""); setNotice(""); }}
              className="flex items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900">
              <Plus aria-hidden className="size-4" />New instructions
            </button>
          ) : (
            <form aria-label="New instructions" className="flex flex-col gap-2 rounded-md bg-zinc-50 p-3 dark:bg-zinc-900/60"
              onSubmit={(event) => { event.preventDefault(); void create(); }}>
              <TextField id="instructions-name" label="Name" autoFocus value={newName} error={nameError}
                hint="For example code-review" onChange={(event) => setNewName(event.target.value.trim())} />
              <div className="flex gap-2">
                <Button label="Create" type="submit" busy={busy} disabled={!newName || nameError !== null} />
                <Button label="Cancel" variant="secondary" onClick={() => setNewName(null)} />
              </div>
            </form>
          )}
        </nav>
        <div className="min-w-0 flex-1 space-y-4">
          {file ? (
            <>
              <InstructionsEditor value={draft} placeholder={INSTRUCTIONS_EXAMPLE}
                onChange={(value) => { setDrafts((current) => ({ ...current, [file.name]: value })); setNotice(""); }} />
              <div className="flex flex-wrap items-center justify-between gap-3 border-t border-zinc-200 pt-4 dark:border-zinc-800">
                <p className="text-xs text-zinc-500 dark:text-zinc-400">{file.name}.md · {draft.length.toLocaleString()} characters</p>
                <div className="flex gap-2">
                  {confirmDelete ? (
                    <>
                      <Button label={`Delete ${file.name}`} variant="danger" busy={busy} onClick={() => void deleteSelected()} />
                      <Button label="Keep" variant="secondary" onClick={() => setConfirmDelete(false)} />
                    </>
                  ) : (
                    <Button label="Delete" variant="secondary" disabled={busy} onClick={() => { setConfirmDelete(true); setNotice(""); }} />
                  )}
                  <Button label="Save" busy={busy && !confirmDelete} disabled={draft === file.content} onClick={() => void submit()} />
                </div>
              </div>
            </>
          ) : (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">No instructions yet. Add some with New instructions.</p>
          )}
          <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
        </div>
      </div>
    </SettingsSection>
  );
}

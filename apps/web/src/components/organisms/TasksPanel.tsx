import { CalendarClock, Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { TASK_NAME, type TaskContent, useTasks } from "../../hooks/useTasks";
import { cronError, validTimeZone } from "../../lib/cron";
import { Button, Spinner, TextField } from "../atoms";
import { SettingsSection } from "../molecules";
// Imported directly, not through the index: the editor is large and only loaded with this panel.
import { InstructionsEditor } from "../molecules/InstructionsEditor";

interface Props {
  readonly workspaceId: string;
}

const DESCRIPTION =
  "Prompts the agent runs on a schedule, each in a new session. The agent can add them too, when you ask it to do something later or regularly.";

const PROMPT_EXAMPLE = `For example:

Check the open pull requests in ~/project and summarise what each one changes.`;

const BROWSER_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
const TIME_ZONES = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

/**
 * The workspace's scheduled tasks: a cron expression, its time zone and the prompt to run. Edits
 * are kept per task until saved, so switching between tasks loses nothing.
 */
export function TasksPanel({ workspaceId }: Props) {
  const { tasks, stopped, error, loading, retry, save, remove } = useTasks(workspaceId);
  const [selected, setSelected] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, TaskContent>>({});
  const [newName, setNewName] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");

  // The first task is open once they have loaded, and another once the open one is gone.
  useEffect(() => {
    if (tasks && (selected === null || !tasks.some((task) => task.name === selected))) {
      setSelected(tasks[0]?.name ?? null);
    }
  }, [tasks, selected]);

  if (loading) {
    return <Spinner label="Loading tasks" className="size-5 text-zinc-400" />;
  }
  if (stopped) {
    return (
      <SettingsSection title="Tasks" description={DESCRIPTION}>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">The tasks live in the workspace. Start it to see and edit them.</p>
      </SettingsSection>
    );
  }
  if (!tasks) {
    return (
      <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div>
    );
  }

  const task = tasks.find((candidate) => candidate.name === selected) ?? null;
  const draft: TaskContent | null = task ? (drafts[task.name] ?? { cron: task.cron, timeZone: task.timeZone, prompt: task.prompt }) : null;
  const changed = task !== null && draft !== null && (draft.cron !== task.cron || draft.timeZone !== task.timeZone || draft.prompt !== task.prompt);
  const scheduleError = draft ? cronError(draft.cron) : null;
  const zoneError = draft && !validTimeZone(draft.timeZone) ? "Use a time zone such as Europe/Sofia." : null;
  const nameError =
    newName === null || newName === "" || TASK_NAME.test(newName)
      ? tasks.some((candidate) => candidate.name === newName) ? "There is a task with this name already." : null
      : "Use up to 63 letters, digits, - and _, starting with a letter or digit.";

  function edit(change: Partial<TaskContent>) {
    if (!task || !draft) return;
    setDrafts((current) => ({ ...current, [task.name]: { ...draft, ...change } }));
    setNotice("");
  }

  async function create() {
    if (!newName || nameError) return;
    setBusy(true);
    if (await save(newName, { cron: "0 9 * * 1-5", timeZone: BROWSER_TIME_ZONE, prompt: "" })) {
      setSelected(newName);
      setNewName(null);
      setNotice("Created. Write what the agent should do, set when, and save.");
    } else {
      setNotice("Could not create the task. Please try again.");
    }
    setBusy(false);
  }

  async function submit() {
    if (!task || !draft || scheduleError || zoneError) return;
    setBusy(true);
    if (await save(task.name, { ...draft, cron: draft.cron.trim(), timeZone: draft.timeZone.trim() })) {
      setDrafts(({ [task.name]: _, ...rest }) => rest);
      setNotice("Saved. It runs at the next matching time while the workspace is running.");
    } else {
      setNotice("Could not save the task. Please try again.");
    }
    setBusy(false);
  }

  async function deleteSelected() {
    if (!task) return;
    setBusy(true);
    if (await remove(task.name)) {
      setDrafts(({ [task.name]: _, ...rest }) => rest);
      setNotice(`Deleted ${task.name}.`);
    } else {
      setNotice("Could not delete the task. Please try again.");
    }
    setConfirmDelete(false);
    setBusy(false);
  }

  return (
    <SettingsSection title="Tasks" description={DESCRIPTION}>
      <div className="flex flex-col gap-4 md:flex-row">
        <nav aria-label="Tasks" className="flex shrink-0 flex-col gap-1 md:w-56">
          {tasks.map(({ name }) => (
            <button key={name} type="button" aria-current={name === selected ? "page" : undefined}
              onClick={() => { setSelected(name); setConfirmDelete(false); setNotice(""); }}
              className={`flex min-w-0 items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium ${name === selected ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900"}`}>
              <CalendarClock aria-hidden className="size-4 shrink-0" />
              <span className="truncate">{name}</span>
              {drafts[name] !== undefined && <span className="ml-auto text-xs text-zinc-400" aria-label="Unsaved changes">•</span>}
            </button>
          ))}
          {newName === null ? (
            <button type="button" onClick={() => { setNewName(""); setNotice(""); }}
              className="flex items-center gap-2 rounded-md px-3 py-2 text-left text-sm font-medium text-zinc-500 hover:bg-zinc-50 dark:text-zinc-400 dark:hover:bg-zinc-900">
              <Plus aria-hidden className="size-4" />New task
            </button>
          ) : (
            <form aria-label="New task" className="flex flex-col gap-2 rounded-md bg-zinc-50 p-3 dark:bg-zinc-900/60"
              onSubmit={(event) => { event.preventDefault(); void create(); }}>
              <TextField id="task-name" surface="solid" label="Name" autoFocus value={newName} error={nameError}
                hint="For example pr-summary" onChange={(event) => setNewName(event.target.value.trim())} />
              <div className="flex gap-2">
                <Button label="Create" type="submit" busy={busy} disabled={!newName || nameError !== null} />
                <Button label="Cancel" variant="secondary" onClick={() => setNewName(null)} />
              </div>
            </form>
          )}
        </nav>
        <div className="min-w-0 flex-1 space-y-4">
          {task && draft ? (
            <>
              <div className="grid gap-4 sm:grid-cols-2">
                <TextField id="task-cron" label="Cron expression" value={draft.cron} error={scheduleError} spellCheck={false}
                  hint="Minute, hour, day of month, month, day of week. 0 9 * * 1-5 is weekdays at 09:00."
                  onChange={(event) => edit({ cron: event.target.value })} />
                <TextField id="task-time-zone" label="Time zone" value={draft.timeZone} error={zoneError} list="task-time-zones"
                  hint="The cron expression is read in this time zone."
                  onChange={(event) => edit({ timeZone: event.target.value })} />
                <datalist id="task-time-zones">
                  {TIME_ZONES.map((zone) => <option key={zone} value={zone} />)}
                </datalist>
              </div>
              <InstructionsEditor fileName={`${task.name}.md`} label="Task prompt" value={draft.prompt} placeholder={PROMPT_EXAMPLE}
                onChange={(prompt) => edit({ prompt })} />
              <div className="flex flex-wrap items-center justify-between gap-3 border-t border-zinc-200 pt-4 dark:border-zinc-800">
                <p className="text-xs text-zinc-500 dark:text-zinc-400">{task.name}.md · {draft.prompt.length.toLocaleString()} characters</p>
                <div className="flex gap-2">
                  {confirmDelete ? (
                    <>
                      <Button label={`Delete ${task.name}`} variant="danger" busy={busy} onClick={() => void deleteSelected()} />
                      <Button label="Keep" variant="secondary" onClick={() => setConfirmDelete(false)} />
                    </>
                  ) : (
                    <Button label="Delete" variant="secondary" disabled={busy} onClick={() => { setConfirmDelete(true); setNotice(""); }} />
                  )}
                  <Button label="Save" busy={busy && !confirmDelete} disabled={!changed || scheduleError !== null || zoneError !== null}
                    onClick={() => void submit()} />
                </div>
              </div>
            </>
          ) : (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">No tasks yet. Add one with New task.</p>
          )}
          <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
        </div>
      </div>
    </SettingsSection>
  );
}

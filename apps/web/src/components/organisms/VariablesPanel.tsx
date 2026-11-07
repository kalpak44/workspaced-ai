import { Plus, RotateCw, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { MAX_VARIABLE_VALUE, MAX_VARIABLES, VARIABLE_NAME, type Variable, useVariables } from "../../hooks/useVariables";
import { Button, IconButton, Spinner } from "../atoms";
import { SettingsSection } from "../molecules";

interface Props {
  readonly workspaceId: string;
  /** While the workspace is already starting, there is nothing to restart. */
  readonly restartDisabled: boolean;
  readonly onRestart: () => void;
}

const DESCRIPTION =
  "Environment variables the workspace starts with, for the agent, the terminal and services. They are kept in the workspace's .env file, which the agent can edit too. A secret of the same name wins; put anything private in Secrets instead, as these values can be read back.";

// Each row keeps an id of its own, so React keeps a row's inputs while others are added or removed.
interface Row extends Variable {
  readonly id: number;
}

let nextId = 0;
const rowsOf = (variables: readonly Variable[]): Row[] => variables.map((variable) => ({ ...variable, id: nextId++ }));

/** Why a row cannot be saved, or null when it can. */
function rowError(row: Row, rows: readonly Row[]): string | null {
  if (!VARIABLE_NAME.test(row.name)) return "Use letters, digits and underscores, not starting with a digit.";
  if (row.name.startsWith("OPENCODE_")) return "Set by the workspace itself.";
  if (rows.some((other) => other.id !== row.id && other.name === row.name)) return "Given twice.";
  if (row.value.length > MAX_VARIABLE_VALUE) return `Up to ${MAX_VARIABLE_VALUE.toLocaleString()} characters.`;
  return null;
}

/** The workspace's .env file as a list of names and values, saved all at once. */
export function VariablesPanel({ workspaceId, restartDisabled, onRestart }: Props) {
  const { variables, stopped, error, loading, retry, save } = useVariables(workspaceId);
  const [rows, setRows] = useState<Row[]>([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (variables) setRows(rowsOf(variables));
  }, [variables]);

  if (loading) {
    return <Spinner label="Loading variables" className="size-5 text-zinc-400" />;
  }
  if (stopped) {
    return (
      <SettingsSection title="Variables" description={DESCRIPTION}>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">The variables live in the workspace. Start it to see and edit them.</p>
      </SettingsSection>
    );
  }
  if (!variables) {
    return (
      <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div>
    );
  }

  const changed =
    rows.length !== variables.length || rows.some((row, index) => row.name !== variables[index].name || row.value !== variables[index].value);
  const invalid = rows.some((row) => rowError(row, rows) !== null);

  function edit(id: number, change: Partial<Variable>) {
    setRows((current) => current.map((row) => (row.id === id ? { ...row, ...change } : row)));
    setNotice("");
  }

  async function submit() {
    setBusy(true);
    if (await save(rows.map(({ name, value }) => ({ name, value })))) {
      setSaved(true);
      setNotice("Saved. The workspace starts with them from its next restart.");
    } else {
      setNotice("Could not save the variables. Please try again.");
    }
    setBusy(false);
  }

  const input =
    "min-w-0 w-full rounded-md border border-zinc-300 bg-transparent px-3 py-2 font-mono text-sm outline-none placeholder:text-zinc-400 focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 aria-invalid:border-rose-500 dark:border-zinc-700 dark:focus:border-zinc-400 dark:focus:ring-white/10";

  return (
    <SettingsSection title="Variables" description={DESCRIPTION}>
      {rows.length > 0 ? (
        <ul aria-label="Variables" className="space-y-2">
          {rows.map((row, index) => {
            const problem = rowError(row, rows);
            return (
              <li key={row.id} className="space-y-1">
                <div className="flex items-center gap-2">
                  <input aria-label={`Name ${index + 1}`} className={`${input} sm:max-w-64`} value={row.name} placeholder="NAME"
                    autoComplete="off" spellCheck={false} maxLength={100} aria-invalid={problem ? true : undefined}
                    onChange={(event) => edit(row.id, { name: event.target.value.trim() })} />
                  <span aria-hidden className="text-zinc-400">=</span>
                  <input aria-label={`Value ${index + 1}`} className={input} value={row.value} placeholder="value"
                    autoComplete="off" spellCheck={false} onChange={(event) => edit(row.id, { value: event.target.value })} />
                  <IconButton label={`Remove ${row.name || "variable"}`} icon={Trash2}
                    onClick={() => { setRows((current) => current.filter((other) => other.id !== row.id)); setNotice(""); }} />
                </div>
                {problem && <p className="text-xs text-rose-600 dark:text-rose-400">{problem}</p>}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="text-sm text-zinc-500 dark:text-zinc-400">No variables yet.</p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-zinc-200 pt-4 dark:border-zinc-800">
        <Button label="Add variable" variant="secondary" icon={Plus} disabled={rows.length >= MAX_VARIABLES}
          onClick={() => { setRows((current) => [...current, ...rowsOf([{ name: "", value: "" }])]); setNotice(""); }} />
        <div className="flex gap-2">
          {saved && <Button label="Restart workspace" variant="secondary" icon={RotateCw} disabled={restartDisabled} onClick={onRestart} />}
          <Button label="Save" busy={busy} disabled={!changed || invalid} onClick={() => void submit()} />
        </div>
      </div>
      <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
    </SettingsSection>
  );
}

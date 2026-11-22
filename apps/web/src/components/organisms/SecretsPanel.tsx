import { reportTourAction } from "../../lib/tour";
import { Plus } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { type AddResult, MAX_SECRETS, type Secret, SECRET_NAME } from "../../hooks/useSecrets";
import { Button, Spinner, TextField } from "../atoms";
import { ErrorBanner, RestartRequiredBanner, SecretItem, SettingsSection } from "../molecules";

const MAX_NAME = 100;
const MAX_VALUE = 65536;

interface Props {
  readonly workspaceId?: string;
  readonly secrets: readonly Secret[];
  readonly loading: boolean;
  readonly removing: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onAdd: (name: string, value: string) => Promise<AddResult>;
  readonly onRemove: (name: string) => void;
  /** Secrets changed since the workspace started, so the running agent does not see them yet. */
  readonly restartRequired: boolean;
  /** While the workspace is already starting, there is nothing to restart. */
  readonly restartDisabled: boolean;
  readonly onRestart: () => void;
}

/** A workspace's secrets by name, and a form to add one; values are write-only. */
export function SecretsPanel({
  workspaceId,
  secrets,
  loading,
  removing,
  error,
  onDismissError,
  onAdd,
  onRemove,
  restartRequired,
  restartDisabled,
  onRestart,
}: Props) {
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [nameError, setNameError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const used = secrets.filter((s) => !s.managed).length;
  const full = used >= MAX_SECRETS;

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (!SECRET_NAME.test(name)) {
      setNameError("Use letters, digits and underscores, not starting with a digit");
      return;
    }
    setPending(true);
    try {
      const result = await onAdd(name, value).catch((): AddResult => "failed");
      if (result === "added") {
        reportTourAction({ type: "secret-saved", workspaceId, name });
        setName("");
        setValue("");
      } else if (result === "conflict") {
        setNameError(`${name} is already set for this workspace`);
      } else if (result === "full") {
        setNameError(`A workspace holds at most ${MAX_SECRETS} secrets`);
      } else {
        setNameError("Could not add the secret");
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <SettingsSection
      title="Secrets"
      description="Passed to the workspace as environment variables when it starts. A value cannot be read back once saved."
    >
      {error && <ErrorBanner message={error} onDismiss={onDismissError} />}
      {restartRequired && <RestartRequiredBanner restartDisabled={restartDisabled} onRestart={onRestart} />}
      {loading ? (
        <Spinner label="Loading secrets" className="size-5 text-zinc-400" />
      ) : (
        <p
          aria-label="Secrets used"
          className={`text-sm ${full ? "text-amber-600 dark:text-amber-400" : "text-zinc-500 dark:text-zinc-400"}`}
        >
          {used} of {MAX_SECRETS} secrets used
          {full && ". Remove one to add another."}
        </p>
      )}
      {!loading && secrets.length > 0 && (
        <ul className="divide-y divide-zinc-200 overflow-hidden rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
          {secrets.map((secret) => (
            <SecretItem
              key={secret.name}
              secret={secret}
              removing={removing.has(secret.name)}
              onRemove={() => onRemove(secret.name)}
            />
          ))}
        </ul>
      )}
      <form data-tour="secret-form" onSubmit={submit} className="flex flex-col gap-3 rounded-md bg-zinc-50 p-4 sm:flex-row sm:items-start dark:bg-zinc-900/60">
        <div className="min-w-0 flex-1">
          <TextField
            id="secret-name"
            surface="solid"
            label="Name"
            placeholder="GITHUB_TOKEN"
            autoComplete="off"
            spellCheck={false}
            value={name}
            maxLength={MAX_NAME}
            disabled={pending || full}
            error={nameError}
            onChange={(e) => {
              setName(e.currentTarget.value);
              setNameError(null);
            }}
          />
        </div>
        <div className="min-w-0 flex-1">
          <TextField
            id="secret-value"
            surface="solid"
            label="Value"
            type="password"
            autoComplete="off"
            value={value}
            maxLength={MAX_VALUE}
            disabled={pending || full}
            onChange={(e) => setValue(e.currentTarget.value)}
          />
        </div>
        <div className="sm:mt-6">
          <Button
            label="Add"
            tourId="add-secret"
            type="submit"
            icon={Plus}
            busy={pending}
            disabled={!name || !value || full}
          />
        </div>
      </form>
    </SettingsSection>
  );
}

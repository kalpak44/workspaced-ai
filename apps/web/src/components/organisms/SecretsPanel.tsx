import { Plus } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { type AddResult, type Secret, SECRET_NAME } from "../../hooks/useSecrets";
import { Button, Spinner, TextField } from "../atoms";
import { ErrorBanner, SecretItem, SettingsSection } from "../molecules";

const MAX_NAME = 100;
const MAX_VALUE = 65536;

interface Props {
  readonly secrets: readonly Secret[];
  readonly loading: boolean;
  readonly removing: ReadonlySet<string>;
  readonly error: string | null;
  readonly onDismissError: () => void;
  readonly onAdd: (name: string, value: string) => Promise<AddResult>;
  readonly onRemove: (name: string) => void;
}

/** A workspace's secrets by name, and a form to add one; values are write-only. */
export function SecretsPanel({
  secrets,
  loading,
  removing,
  error,
  onDismissError,
  onAdd,
  onRemove,
}: Props) {
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [nameError, setNameError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

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
        setName("");
        setValue("");
      } else if (result === "conflict") {
        setNameError(`${name} is already set for this workspace`);
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
      {loading ? (
        <Spinner label="Loading secrets" className="size-5 text-zinc-400" />
      ) : (
        <ul className="divide-y divide-zinc-200 rounded-md border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
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
      <form onSubmit={submit} className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <div className="flex-1">
          <TextField
            id="secret-name"
            label="Name"
            placeholder="GITHUB_TOKEN"
            autoComplete="off"
            spellCheck={false}
            value={name}
            maxLength={MAX_NAME}
            disabled={pending}
            error={nameError}
            onChange={(e) => {
              setName(e.currentTarget.value);
              setNameError(null);
            }}
          />
        </div>
        <div className="flex-1">
          <TextField
            id="secret-value"
            label="Value"
            type="password"
            autoComplete="off"
            value={value}
            maxLength={MAX_VALUE}
            disabled={pending}
            onChange={(e) => setValue(e.currentTarget.value)}
          />
        </div>
        <div className="sm:mt-6">
          <Button
            label="Add"
            type="submit"
            icon={Plus}
            busy={pending}
            disabled={!name || !value}
          />
        </div>
      </form>
    </SettingsSection>
  );
}

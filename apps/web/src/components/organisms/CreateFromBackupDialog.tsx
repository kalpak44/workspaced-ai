import { CopyPlus } from "lucide-react";
import { type SubmitEvent, useState } from "react";
import { Button, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly initialName: string;
  /** While the backed-up workspace exists, its key is reused when none is given. */
  readonly canReuseKey: boolean;
  readonly secrets: readonly string[];
  /** Resolves null once created, or the reason it was not. */
  readonly onCreate: (name: string, apiKey: string) => Promise<string | null>;
  readonly onClose: () => void;
}

export function CreateFromBackupDialog({ initialName, canReuseKey, secrets, onCreate, onClose }: Props) {
  const [name, setName] = useState(initialName);
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const failure = await onCreate(name.trim(), apiKey.trim());
    if (failure === null) {
      onClose();
    } else {
      setError(failure);
      setBusy(false);
    }
  };

  return (
    <Modal title="New workspace from backup" description="It starts with the backup's files, conversations and settings." onClose={() => !busy && onClose()}>
      <form onSubmit={submit} className="flex min-h-0 flex-col overflow-hidden">
        <ModalBody>
          <TextField id="backup-workspace-name" label="Name" value={name} maxLength={100} disabled={busy} onChange={(e) => setName(e.currentTarget.value)} />
          <TextField id="backup-workspace-key" label="API key" type="password" autoComplete="off" spellCheck={false}
            placeholder={canReuseKey ? "Leave empty to reuse the original workspace's key" : "Paste your key"}
            value={apiKey} maxLength={1000} disabled={busy} error={error}
            hint={canReuseKey ? "Optional. Checked with the provider before the workspace is created." : "Required: the original workspace is gone, so there is no key to reuse."}
            onChange={(e) => { setApiKey(e.currentTarget.value); setError(null); }} />
          {secrets.length > 0 && (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              Secrets are never backed up. Add these again in the new workspace's settings: <span className="font-mono text-xs">{secrets.join(", ")}</span>
            </p>
          )}
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} disabled={busy} />
          <Button label="Create workspace" type="submit" icon={CopyPlus} busy={busy} disabled={!name.trim() || (!canReuseKey && !apiKey.trim())} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

import { ArrowRight } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import type { ModelProvider } from "../../hooks/useModelProviders";
import type { CreateResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, Select, TextField } from "../atoms";
import { Modal } from "../molecules";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;
const MAX_KEY = 1000;

const PLACEHOLDERS = {
  loading: "Loading providers…",
  ready: "Choose a provider",
  failed: "Could not load providers",
};

interface Props {
  readonly initialName: string;
  readonly providers: readonly ModelProvider[];
  /** While the providers are still being fetched, or if fetching them failed. */
  readonly providersStatus: "loading" | "ready" | "failed";
  /** Resolves "created" once it exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (workspace: NewWorkspace) => Promise<CreateResult>;
  readonly onClose: () => void;
}

/**
 * Name, then provider, then that provider's key: the key field appears only once a provider
 * is chosen, because the key it asks for depends on which. The API checks the key before it
 * creates anything, so a wrong one is reported here rather than as a broken workspace.
 */
export function CreateWorkspaceDialog({
  initialName,
  providers,
  providersStatus,
  onCreate,
  onClose,
}: Props) {
  const [name, setName] = useState(initialName);
  const [providerId, setProviderId] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [rejected, setRejected] = useState(false);
  const [pending, setPending] = useState(false);
  const nameInput = useRef<HTMLInputElement>(null);
  const keyInput = useRef<HTMLInputElement>(null);
  const provider = providers.find((p) => p.id === providerId);

  // The suggested name is selected, so typing replaces it and Enter moves on.
  useEffect(() => {
    nameInput.current?.focus();
    nameInput.current?.select();
  }, []);

  // The key field appears on the user's own pick, so focus follows it there.
  useEffect(() => {
    if (providerId) {
      keyInput.current?.focus();
    }
  }, [providerId]);

  // Closing mid-request would hide whether the workspace was made, so it waits.
  const close = () => {
    if (!pending) {
      onClose();
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    setPending(true);
    setRejected(false);
    try {
      const result = await onCreate({ name, modelProvider: providerId, apiKey }).catch(
        (): CreateResult => "failed",
      );
      if (result === "created") {
        onClose();
      } else if (result === "rejected") {
        setRejected(true);
        keyInput.current?.focus();
      }
    } finally {
      setPending(false);
    }
  };

  return (
    <Modal title="New workspace" onClose={close}>
      <form onSubmit={submit} className="flex flex-col gap-5">
        <TextField
          ref={nameInput}
          id="workspace-name"
          label="Name"
          value={name}
          maxLength={MAX_NAME}
          disabled={pending}
          onChange={(e) => setName(e.currentTarget.value)}
        />
        <Select
          id="workspace-provider"
          label="Model provider"
          placeholder={PLACEHOLDERS[providersStatus]}
          options={providers.map((p) => ({ value: p.id, label: p.name }))}
          value={providerId}
          disabled={pending || providersStatus !== "ready"}
          onChange={(e) => {
            setProviderId(e.currentTarget.value);
            setRejected(false);
          }}
        />
        {provider && (
          <TextField
            ref={keyInput}
            id="workspace-api-key"
            label={`${provider.name} API key`}
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={apiKey}
            maxLength={MAX_KEY}
            disabled={pending}
            error={rejected ? `${provider.name} did not accept this key` : null}
            onChange={(e) => {
              setApiKey(e.currentTarget.value);
              setRejected(false);
            }}
          />
        )}
        <div className="flex justify-end gap-2">
          <Button label="Cancel" variant="secondary" onClick={onClose} disabled={pending} />
          <Button
            label="Continue"
            type="submit"
            icon={ArrowRight}
            busy={pending}
            disabled={!name.trim() || !provider || !apiKey.trim() || rejected}
          />
        </div>
      </form>
    </Modal>
  );
}

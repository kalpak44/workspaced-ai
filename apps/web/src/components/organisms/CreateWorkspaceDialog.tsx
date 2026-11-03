import { ArrowRight, Sparkles } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import type { ModelProvider } from "../../hooks/useModelProviders";
import type { CreateResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, ChoiceCard, Spinner, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;
const MAX_KEY = 1000;

/** The host alone, which is what tells two providers apart at a glance. */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

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

  let providerChoices;
  if (providersStatus === "loading") {
    providerChoices = (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-zinc-300 px-4 py-3 text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
        <Spinner label="Loading providers" />
        Loading providers…
      </div>
    );
  } else if (providersStatus === "failed") {
    providerChoices = (
      <p className="rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">
        Could not load providers. Close this and try again.
      </p>
    );
  } else {
    providerChoices = providers.map((p) => (
      <ChoiceCard
        key={p.id}
        name="workspace-provider"
        value={p.id}
        label={p.name}
        detail={hostOf(p.baseUrl)}
        icon={Sparkles}
        checked={p.id === providerId}
        disabled={pending}
        onChange={(id) => {
          setProviderId(id);
          setRejected(false);
        }}
      />
    ));
  }

  return (
    <Modal
      title="New workspace"
      description="Name it and connect the model provider its agent will use."
      onClose={close}
    >
      <form onSubmit={submit}>
        <ModalBody>
          <TextField
            ref={nameInput}
            id="workspace-name"
            label="Name"
            value={name}
            maxLength={MAX_NAME}
            disabled={pending}
            onChange={(e) => setName(e.currentTarget.value)}
          />
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">
              Model provider
            </legend>
            {providerChoices}
          </fieldset>
          {provider && (
            <TextField
              ref={keyInput}
              id="workspace-api-key"
              label={`${provider.name} API key`}
              type="password"
              placeholder="Paste your key"
              autoComplete="off"
              spellCheck={false}
              value={apiKey}
              maxLength={MAX_KEY}
              disabled={pending}
              hint={`Checked with ${provider.name} before the workspace is created, then kept only in the workspace.`}
              error={rejected ? `${provider.name} did not accept this key` : null}
              onChange={(e) => {
                setApiKey(e.currentTarget.value);
                setRejected(false);
              }}
            />
          )}
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} disabled={pending} />
          <Button
            label="Continue"
            type="submit"
            icon={ArrowRight}
            busy={pending}
            disabled={!name.trim() || !provider || !apiKey.trim() || rejected}
          />
        </ModalFooter>
      </form>
    </Modal>
  );
}

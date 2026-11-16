import { ArrowLeft, ArrowRight, Check, Sparkles, Wallet } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { useModelProviders } from "../../hooks/useModelProviders";
import { navigate } from "../../hooks/useRoute";
import { routePath } from "../../lib/routes";
import { useVmSizes } from "../../hooks/useVmSizes";
import type { CreateResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, ChoiceCard, Spinner, TextField } from "../atoms";
import { initialVmSize, Modal, ModalBody, ModalFooter, VmSizePicker } from "../molecules";

const PRICE = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" });

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;
const MAX_KEY = 1000;

const STEPS = ["Name", "VM type", "Model provider"] as const;
const LAST = STEPS.length - 1;

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
  /** Resolves "created" once it exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (workspace: NewWorkspace) => Promise<CreateResult>;
  readonly onClose: () => void;
}

/**
 * Three steps: the name, the size of the VM it runs on, then the model provider and its key. The
 * sizes are fetched as the dialog opens, since they come from Hetzner and can change, and the
 * default one starts out chosen while Hetzner has it; so does the provider while there is only one.
 * A size that sells out before the create brings the dialog back to the sizes. The API checks the
 * key before it creates anything, so a wrong one is reported here rather than as a broken
 * workspace.
 */
export function CreateWorkspaceDialog({ initialName, onCreate, onClose }: Props) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState(initialName);
  const [serverType, setServerType] = useState("");
  const [providerId, setProviderId] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [rejected, setRejected] = useState(false);
  const [pending, setPending] = useState(false);
  const [soldOut, setSoldOut] = useState<string | null>(null);
  const [noFunds, setNoFunds] = useState(false);
  const { sizes, status, reload } = useVmSizes();
  const chosen = sizes.find((size) => size.serverType === serverType);
  const providers = useModelProviders();
  const provider = providers.providers.find((p) => p.id === providerId);
  const nameInput = useRef<HTMLInputElement>(null);
  const types = useRef<HTMLFieldSetElement>(null);
  const keyInput = useRef<HTMLInputElement>(null);

  // The suggested name is selected, so typing replaces it and Enter moves on.
  useEffect(() => {
    if (step === 0) {
      nameInput.current?.focus();
      nameInput.current?.select();
    }
  }, [step]);

  // A size that is sold out after a reload cannot stay chosen.
  useEffect(() => {
    if (status !== "ready") return;
    if (!serverType || !sizes.some((s) => s.serverType === serverType && s.available)) {
      setServerType(initialVmSize(sizes));
    }
  }, [sizes, status, serverType]);

  useEffect(() => {
    if (!providerId && providers.providers.length === 1) {
      setProviderId(providers.providers[0].id);
    }
  }, [providers.providers, providerId]);

  // The key is what is left to type once a provider is chosen, so focus waits there.
  useEffect(() => {
    if (step === LAST && provider) {
      keyInput.current?.focus();
    }
  }, [step, provider]);

  // The name field is gone once the step moves on, so focus follows to the chosen type and
  // Enter creates the workspace from there.
  useEffect(() => {
    if (step === 1 && serverType) {
      types.current?.querySelector<HTMLInputElement>("input:checked")?.focus();
    }
  }, [step, serverType]);

  // Closing mid-request would hide whether the workspace was made, so it waits.
  const close = () => {
    if (!pending) {
      onClose();
    }
  };

  const submit = async (event: SubmitEvent) => {
    event.preventDefault();
    if (step < LAST) {
      setStep(step + 1);
      return;
    }
    setPending(true);
    setRejected(false);
    setNoFunds(false);
    try {
      const result = await onCreate({ name, serverType, modelProvider: providerId, apiKey }).catch(
        (): CreateResult => "failed",
      );
      if (result === "created") {
        onClose();
      } else if (result === "rejected") {
        setRejected(true);
        keyInput.current?.focus();
      } else if (result === "noFunds") {
        setNoFunds(true);
      } else if (result === "soldOut") {
        setSoldOut(serverType);
        setServerType("");
        setStep(1);
        reload();
      }
    } finally {
      setPending(false);
    }
  };

  let providerChoices;
  if (providers.status === "loading") {
    providerChoices = (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-zinc-300 px-4 py-3 text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
        <Spinner label="Loading providers" />
        Loading providers…
      </div>
    );
  } else if (providers.status === "failed" || providers.providers.length === 0) {
    providerChoices = (
      <p className="rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">
        Could not load providers. Close this and try again.
      </p>
    );
  } else {
    providerChoices = providers.providers.map((p) => (
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

  let stepBody;
  if (step === 0) {
    stepBody = (
      <TextField
        ref={nameInput}
        id="workspace-name"
        label="Name"
        value={name}
        maxLength={MAX_NAME}
        onChange={(e) => setName(e.currentTarget.value)}
      />
    );
  } else if (step === 1) {
    stepBody = (
      <fieldset ref={types} className="flex min-w-0 flex-col gap-2">
        <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">VM type</legend>
        <VmSizePicker
          sizes={sizes}
          status={status}
          value={serverType}
          disabled={pending}
          soldOut={soldOut}
          onChange={(type) => {
            setServerType(type);
            setSoldOut(null);
            setNoFunds(false);
          }}
          onReload={reload}
        />
      </fieldset>
    );
  } else {
    stepBody = (
      <>
        <fieldset className="flex min-w-0 flex-col gap-2">
          <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">Model provider</legend>
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
            hint={`Checked with ${provider.name} before the workspace is created, then kept as a workspace secret that cannot be removed.`}
            error={rejected ? `${provider.name} did not accept this key` : null}
            onChange={(e) => {
              setApiKey(e.currentTarget.value);
              setRejected(false);
            }}
          />
        )}
        {noFunds && (
          <div role="alert" className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-6 text-zinc-700 dark:border-amber-950 dark:bg-amber-950/30 dark:text-zinc-300">
            <p>
              Your balance does not cover a month of {serverType}
              {chosen ? ` (${PRICE.format(chosen.monthlyGross)} a month)` : ""}. Add funds, then create the workspace.
            </p>
            <a
              href={routePath({ name: "billing" })}
              onClick={(event) => {
                event.preventDefault();
                onClose();
                navigate({ name: "billing" });
              }}
              className="inline-flex w-fit items-center gap-1.5 rounded-lg border border-zinc-300 bg-white px-3 py-1.5 font-medium text-zinc-900 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:bg-zinc-800"
            >
              <Wallet aria-hidden className="size-4" />
              Add funds
            </a>
          </div>
        )}
      </>
    );
  }

  let next;
  if (step === 0) {
    next = <Button label="Next" type="submit" icon={ArrowRight} disabled={!name.trim()} />;
  } else if (step === 1) {
    next = <Button label="Next" type="submit" icon={ArrowRight} disabled={!serverType || status !== "ready"} />;
  } else {
    next = (
      <Button
        label="Create workspace"
        type="submit"
        icon={ArrowRight}
        busy={pending}
        disabled={!provider || !apiKey.trim() || rejected}
      />
    );
  }

  return (
    <Modal
      title="New workspace"
      description="Name it, choose the VM it runs on, then connect the model provider its agent will use."
      onClose={close}
    >
      <form onSubmit={submit} className="flex min-h-0 flex-col overflow-hidden">
        <ModalBody>
          <ol aria-label="Steps" className="flex items-center gap-2 text-xs font-medium">
            {STEPS.map((label, index) => {
              const reached = index <= step;
              return (
                <li
                  key={label}
                  aria-current={index === step ? "step" : undefined}
                  className={`flex items-center gap-2 ${
                    reached ? "text-zinc-900 dark:text-zinc-100" : "text-zinc-400 dark:text-zinc-500"
                  }`}
                >
                  {index > 0 && <span aria-hidden className="h-px w-6 bg-zinc-300 dark:bg-zinc-700" />}
                  <span
                    aria-hidden
                    className={`flex size-5 items-center justify-center rounded-full border ${
                      reached
                        ? "border-zinc-900 bg-zinc-900 text-white dark:border-white dark:bg-white dark:text-zinc-900"
                        : "border-zinc-300 dark:border-zinc-600"
                    }`}
                  >
                    {index < step ? <Check className="size-3" strokeWidth={3} /> : index + 1}
                  </span>
                  {label}
                </li>
              );
            })}
          </ol>
          {stepBody}
        </ModalBody>
        <ModalFooter>
          {step === 0 ? (
            <Button label="Cancel" variant="secondary" onClick={onClose} />
          ) : (
            <Button
              label="Back"
              variant="secondary"
              icon={ArrowLeft}
              onClick={() => setStep(step - 1)}
              disabled={pending}
            />
          )}
          {next}
        </ModalFooter>
      </form>
    </Modal>
  );
}

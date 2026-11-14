import { ArrowLeft, ArrowRight, Check, Server } from "lucide-react";
import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { useVmSizes, type VmSize } from "../../hooks/useVmSizes";
import type { CreateResult, NewWorkspace } from "../../hooks/useWorkspaces";
import { Button, ChoiceCard, Spinner, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

// The API rejects anything longer, so the field never lets the user type past it.
const MAX_NAME = 100;

const STEPS = ["Name", "VM type"] as const;

/** What the VM is, without its price: cores, memory and disk. */
function resources(size: VmSize): string {
  return `${size.cores} vCPU · ${size.memoryGb} GB RAM · ${size.diskGb} GB disk`;
}

interface Props {
  readonly initialName: string;
  /** Resolves "created" once it exists; on anything else the dialog stays and keeps its input. */
  readonly onCreate: (workspace: NewWorkspace) => Promise<CreateResult>;
  readonly onClose: () => void;
}

/**
 * Two steps: the name, then the size of the VM it runs on. The sizes are fetched as the dialog
 * opens, since they come from Hetzner and can change, and the default one starts out chosen.
 */
export function CreateWorkspaceDialog({ initialName, onCreate, onClose }: Props) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState(initialName);
  const [serverType, setServerType] = useState("");
  const [pending, setPending] = useState(false);
  const { sizes, status } = useVmSizes();
  const nameInput = useRef<HTMLInputElement>(null);
  const types = useRef<HTMLFieldSetElement>(null);

  // The suggested name is selected, so typing replaces it and Enter moves on.
  useEffect(() => {
    if (step === 0) {
      nameInput.current?.focus();
      nameInput.current?.select();
    }
  }, [step]);

  useEffect(() => {
    if (!serverType && sizes.length > 0) {
      setServerType((sizes.find((s) => s.isDefault) ?? sizes[0]).serverType);
    }
  }, [sizes, serverType]);

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
    if (step === 0) {
      setStep(1);
      return;
    }
    setPending(true);
    try {
      const result = await onCreate({ name, serverType }).catch((): CreateResult => "failed");
      if (result === "created") {
        onClose();
      }
    } finally {
      setPending(false);
    }
  };

  let sizeChoices;
  if (status === "loading") {
    sizeChoices = (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-zinc-300 px-4 py-3 text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
        <Spinner label="Loading VM types" />
        Loading VM types…
      </div>
    );
  } else if (status === "failed" || sizes.length === 0) {
    sizeChoices = (
      <p className="rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">
        Could not load VM types. Close this and try again.
      </p>
    );
  } else {
    sizeChoices = sizes.map((size) => (
      <ChoiceCard
        key={size.serverType}
        name="workspace-vm-type"
        value={size.serverType}
        label={size.serverType}
        detail={resources(size)}
        icon={Server}
        checked={size.serverType === serverType}
        disabled={pending}
        onChange={setServerType}
      />
    ));
  }

  return (
    <Modal title="New workspace" description="Name it, then choose the VM it runs on." onClose={close}>
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
          {step === 0 ? (
            <TextField
              ref={nameInput}
              id="workspace-name"
              label="Name"
              value={name}
              maxLength={MAX_NAME}
              onChange={(e) => setName(e.currentTarget.value)}
            />
          ) : (
            <fieldset ref={types} className="flex min-w-0 flex-col gap-2">
              <legend className="mb-1.5 text-sm font-medium text-zinc-700 dark:text-zinc-300">VM type</legend>
              {sizeChoices}
            </fieldset>
          )}
        </ModalBody>
        <ModalFooter>
          {step === 0 ? (
            <Button label="Cancel" variant="secondary" onClick={onClose} />
          ) : (
            <Button label="Back" variant="secondary" icon={ArrowLeft} onClick={() => setStep(0)} disabled={pending} />
          )}
          {step === 0 ? (
            <Button label="Next" type="submit" icon={ArrowRight} disabled={!name.trim()} />
          ) : (
            <Button
              label="Create workspace"
              type="submit"
              icon={ArrowRight}
              busy={pending}
              disabled={!serverType || status !== "ready"}
            />
          )}
        </ModalFooter>
      </form>
    </Modal>
  );
}

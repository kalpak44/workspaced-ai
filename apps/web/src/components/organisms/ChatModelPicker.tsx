import { ChevronDown } from "lucide-react";
import { useState } from "react";
import { useModelSettings } from "../../hooks/useModelSettings";
import type { Workspace } from "../../hooks/useWorkspaces";
import { Button } from "../atoms";
import { Modal, ModalBody } from "../molecules";
import { ModelPanel } from "./ModelPanel";

interface Props {
  readonly workspace: Workspace;
  readonly disabled?: boolean;
  readonly onChanged: (model: string | null) => void;
}

/** The workspace model stays within reach of both new tasks and existing conversations. */
export function ChatModelPicker({ workspace, disabled = false, onChanged }: Props) {
  const [open, setOpen] = useState(false);
  const ready = workspace.status === "READY";
  const models = useModelSettings(ready ? workspace.id : null, workspace.model, onChanged);
  const selected = workspace.model ?? models.catalogue?.defaultModel;
  const name = models.catalogue?.models.find((m) => m.id === selected)?.name ?? selected ?? "Default model";
  return (
    <>
      <button
        type="button"
        aria-label={`Model: ${name}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        title={`Choose a model · ${name}`}
        disabled={disabled || models.saving !== undefined}
        onClick={() => setOpen(true)}
        className="flex min-w-0 items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-normal text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-400 disabled:cursor-not-allowed disabled:opacity-40 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
      >
        <span className="truncate">{name}</span>
        <ChevronDown aria-hidden className="size-3 shrink-0 text-zinc-400" />
      </button>
      {open && (
        <Modal title="Choose a model" onClose={() => setOpen(false)}>
          <ModalBody>
            <ModelPanel catalogue={models.catalogue} current={workspace.model} available={ready}
              loading={models.loading} saving={models.saving} error={models.error}
              onDismissError={models.dismissError} onChoose={models.choose} />
            <Button label="Done" variant="secondary" onClick={() => setOpen(false)} />
          </ModalBody>
        </Modal>
      )}
    </>
  );
}

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
      <Button label={`Model: ${name}`} variant="secondary" disabled={disabled || models.saving !== undefined}
        onClick={() => setOpen(true)} />
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

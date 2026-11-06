import { RotateCw } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

/** Says what a restart keeps and what it interrupts before the pod is replaced. */
export function RestartWorkspaceDialog({ name, onConfirm, onClose }: Props) {
  return (
    <Modal
      title={`Restart ${name}?`}
      description="The workspace will be unavailable for a moment."
      onClose={onClose}
    >
      <ModalBody>
        <div className="flex gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 dark:border-amber-950 dark:bg-amber-950/30">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-400">
            <RotateCw aria-hidden className="size-4.5" />
          </span>
          <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
            The workspace pod will be replaced with a fresh one. Sessions, files, and secrets
            are kept; running agent replies, terminals, and processes are stopped.
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" onClick={onClose} autoFocus />
        <Button label="Restart workspace" icon={RotateCw} onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

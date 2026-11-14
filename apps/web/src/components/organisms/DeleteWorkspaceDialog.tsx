import { AlertTriangle, Trash2 } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

/** Makes the scope of workspace deletion explicit before the irreversible API call. */
export function DeleteWorkspaceDialog({ name, onConfirm, onClose }: Props) {
  return (
    <Modal
      title={`Delete ${name}?`}
      description="This action is permanent and cannot be undone."
      onClose={onClose}
    >
      <ModalBody>
        <div className="flex gap-3 rounded-xl border border-rose-200 bg-rose-50/70 p-4 dark:border-rose-950 dark:bg-rose-950/30">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-rose-100 text-rose-600 dark:bg-rose-950 dark:text-rose-400">
            <AlertTriangle aria-hidden className="size-4.5" />
          </span>
          <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
            The workspace's VM and its disk will be deleted, with everything stored on it,
            including sessions, files, secrets, and workspace configuration.
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" onClick={onClose} autoFocus />
        <Button label="Delete workspace" variant="danger" icon={Trash2} onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

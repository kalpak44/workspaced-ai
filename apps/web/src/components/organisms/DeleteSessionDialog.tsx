import { Trash2 } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly title: string;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

export function DeleteSessionDialog({ title, onConfirm, onClose }: Props) {
  return (
    <Modal title="Delete session?" description="This action cannot be undone." onClose={onClose}>
      <ModalBody>
        <p className="text-sm leading-relaxed text-zinc-600 dark:text-zinc-300">Delete the conversation <strong className="font-medium text-zinc-900 dark:text-zinc-100">{title}</strong>? Your workspace and its files will be kept.</p>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" autoFocus onClick={onClose} />
        <Button label="Delete session" icon={Trash2} variant="danger" onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

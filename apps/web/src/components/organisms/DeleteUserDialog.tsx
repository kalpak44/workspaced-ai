import { Trash2 } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly username: string;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

export function DeleteUserDialog({ username, onConfirm, onClose }: Props) {
  return (
    <Modal title="Delete user?" description="This action cannot be undone." onClose={onClose}>
      <ModalBody>
        <p className="text-sm leading-relaxed text-zinc-600 dark:text-zinc-300">Delete <strong className="font-medium text-zinc-900 dark:text-zinc-100">{username}</strong>? They will no longer be able to sign in.</p>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" autoFocus onClick={onClose} />
        <Button label="Delete user" icon={Trash2} variant="danger" onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

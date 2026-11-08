import { Trash2 } from "lucide-react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  /** The one session's title; leave it out to confirm `count` sessions at once. */
  readonly title?: string;
  readonly count?: number;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

export function DeleteSessionDialog({ title, count = 1, onConfirm, onClose }: Props) {
  const many = title === undefined;
  const sessions = count === 1 ? "1 session" : `${count} sessions`;
  return (
    <Modal title={many ? `Delete ${sessions}?` : "Delete session?"} description="This action cannot be undone." onClose={onClose}>
      <ModalBody>
        <p className="text-sm leading-relaxed text-zinc-600 dark:text-zinc-300">
          {many ? (
            <>Delete <strong className="font-medium text-zinc-900 dark:text-zinc-100">{count === 1 ? "1 conversation" : `${count} conversations`}</strong>?</>
          ) : (
            <>Delete the conversation <strong className="font-medium text-zinc-900 dark:text-zinc-100">{title}</strong>?</>
          )}{" "}
          Your workspace and its files will be kept.
        </p>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" autoFocus onClick={onClose} />
        <Button label={many ? `Delete ${sessions}` : "Delete session"} icon={Trash2} variant="danger" onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

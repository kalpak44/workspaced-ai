import { type LucideIcon, Trash2 } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "./Modal";

interface Props {
  readonly title: string;
  readonly description: string;
  /** What is about to go, in a sentence. */
  readonly children: ReactNode;
  readonly confirmLabel: string;
  /** On the confirm button; a trash can unless what goes is not deleted, as when leaving. */
  readonly confirmIcon?: LucideIcon;
  readonly busy: boolean;
  readonly onClose: () => void;
  readonly onConfirm: () => void;
}

/** The one confirmation every list item's trash button opens; Cancel has focus, so Enter is safe. */
export function ConfirmDeleteDialog({ title, description, children, confirmLabel, confirmIcon = Trash2, busy, onClose, onConfirm }: Props) {
  return (
    <Modal title={title} description={description} onClose={() => { if (!busy) onClose(); }}>
      <ModalBody><p className="text-sm text-zinc-600 dark:text-zinc-300">{children}</p></ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" autoFocus disabled={busy} onClick={onClose} />
        <Button label={confirmLabel} variant="danger" icon={confirmIcon} busy={busy} onClick={onConfirm} />
      </ModalFooter>
    </Modal>
  );
}

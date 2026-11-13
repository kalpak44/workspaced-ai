import { AlertTriangle, Trash2 } from "lucide-react";
import { useState } from "react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly createdAt: string;
  /** An upload still running is cancelled rather than deleted. */
  readonly uploading: boolean;
  readonly onConfirm: () => Promise<boolean>;
  readonly onClose: () => void;
}

export function DeleteBackupDialog({ createdAt, uploading, onConfirm, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={uploading ? "Cancel this upload?" : "Delete this backup?"} description={new Date(createdAt).toLocaleString()} onClose={() => !busy && onClose()}>
      <ModalBody>
        <div className="flex gap-3 rounded-xl border border-rose-200 bg-rose-50/70 p-4 dark:border-rose-950 dark:bg-rose-950/30">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-rose-100 text-rose-600 dark:bg-rose-950 dark:text-rose-400">
            <AlertTriangle aria-hidden className="size-4.5" />
          </span>
          <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
            {uploading
              ? "The parts sent so far are discarded. The workspace is not changed."
              : "The archive is removed from storage and cannot be restored afterwards. Download it first to keep a copy."}
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label={uploading ? "Keep uploading" : "Cancel"} variant="secondary" onClick={onClose} disabled={busy} autoFocus />
        <Button label={uploading ? "Cancel upload" : "Delete backup"} variant="danger" icon={Trash2} busy={busy} onClick={() => {
          setBusy(true);
          void onConfirm().then((ok) => (ok ? onClose() : setBusy(false)));
        }} />
      </ModalFooter>
    </Modal>
  );
}

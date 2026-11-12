import { History } from "lucide-react";
import { useState } from "react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly createdAt: string;
  readonly onConfirm: () => Promise<boolean>;
  readonly onClose: () => void;
}

/** Restoring replaces the whole home, so it says so before anything is touched. */
export function RestoreBackupDialog({ name, createdAt, onConfirm, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={`Restore ${name}?`} description={`To the backup from ${new Date(createdAt).toLocaleString()}.`} onClose={() => !busy && onClose()}>
      <ModalBody>
        <div className="flex gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 dark:border-amber-950 dark:bg-amber-950/30">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-400">
            <History aria-hidden className="size-4.5" />
          </span>
          <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
            Every file, conversation, instruction and task in the workspace is replaced by the
            backup's, and the workspace restarts. Secrets keep their current values.
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" onClick={onClose} disabled={busy} autoFocus />
        <Button label="Restore workspace" icon={History} busy={busy} onClick={() => {
          setBusy(true);
          void onConfirm().then((ok) => (ok ? onClose() : setBusy(false)));
        }} />
      </ModalFooter>
    </Modal>
  );
}

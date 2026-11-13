import { Archive } from "lucide-react";
import { useState } from "react";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly onConfirm: () => Promise<boolean>;
  readonly onClose: () => void;
}

/** Says what a backup holds, and what it leaves out, before one is started. */
export function ConfirmBackupDialog({ name, onConfirm, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={`Back up ${name}?`} description="Everything in the workspace's home, with its settings." onClose={() => !busy && onClose()}>
      <ModalBody>
        <div className="flex gap-3 rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 dark:border-zinc-800 dark:bg-zinc-950/30">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">
            <Archive aria-hidden className="size-4.5" />
          </span>
          <p className="pt-0.5 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
            Files, conversations, instructions, tasks and installed tools are archived while the
            workspace keeps running. Secret values are never backed up, only their names, so
            they have to be added again after a restore elsewhere.
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" onClick={onClose} disabled={busy} autoFocus />
        <Button label="Back up now" icon={Archive} busy={busy} onClick={() => {
          setBusy(true);
          void onConfirm().then((ok) => (ok ? onClose() : setBusy(false)));
        }} />
      </ModalFooter>
    </Modal>
  );
}

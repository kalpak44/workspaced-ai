import { Archive, CheckCircle2 } from "lucide-react";
import type { Backup } from "../../hooks/useBackupRun";
import { formatBytes } from "../../lib/format";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly backup: Backup | null;
  readonly error: string | null;
  readonly onClose: () => void;
  readonly onShowBackups: () => void;
}

const PHASES: Readonly<Record<string, string>> = {
  preparing: "Recording the workspace's settings…",
  archiving: "Packing files, conversations and installed tools…",
  uploading: "Uploading to backup storage…",
};

/** Follows one backup to the end. Closing it does not stop the backup; the API keeps going. */
export function BackupDialog({ name, backup, error, onClose, onShowBackups }: Props) {
  const failed = error ?? (backup?.status === "FAILED" ? backup.error ?? "The backup failed." : null);
  const done = backup?.status === "COMPLETED";
  const progress = done ? 100 : (backup?.progress ?? 0);
  let detail = PHASES[backup?.phase ?? "preparing"] ?? "Working…";
  if (done) detail = `Saved ${backup.sizeBytes === null ? "" : formatBytes(backup.sizeBytes)}`.trim();
  return (
    <Modal title={`Back up ${name}`} description="Everything in the workspace's home, with its settings." onClose={onClose}>
      <ModalBody>
        {failed ? (
          <p role="alert" className="rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">{failed}</p>
        ) : (
          <div className="space-y-3">
            <div className="flex items-center gap-3 text-sm">
              {done
                ? <CheckCircle2 aria-hidden className="size-4.5 shrink-0 text-emerald-500" />
                : <Archive aria-hidden className="size-4.5 shrink-0 text-zinc-400" />}
              <span className="min-w-0 flex-1 text-zinc-700 dark:text-zinc-300">{detail}</span>
              <span className="tabular-nums text-zinc-500">{progress}%</span>
            </div>
            <div role="progressbar" aria-label="Backup progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}
              className="h-2 overflow-hidden rounded-full bg-zinc-200 dark:bg-zinc-800">
              <div className={`h-full rounded-full transition-[width] duration-500 ${done ? "bg-emerald-500" : "bg-zinc-900 dark:bg-white"}`} style={{ width: `${progress}%` }} />
            </div>
            {!done && <p className="text-xs text-zinc-500 dark:text-zinc-400">You can close this; the backup carries on and appears under Backups when it is done.</p>}
          </div>
        )}
      </ModalBody>
      <ModalFooter>
        <Button label={done || failed ? "Close" : "Hide"} variant="secondary" onClick={onClose} />
        <Button label="View backups" onClick={onShowBackups} />
      </ModalFooter>
    </Modal>
  );
}

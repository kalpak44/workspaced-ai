import { CopyPlus, Download, History, Trash2, X } from "lucide-react";
import type { Backup, Upload } from "../../hooks/useBackups";
import { formatBytes } from "../../lib/format";
import { Button, ProgressBar } from "../atoms";

interface Props {
  readonly backups: readonly Backup[];
  /** False while the workspace is starting, when a second restart would only queue behind it. */
  readonly canRestore: boolean;
  /** The upload this tab is sending, whose row shows its own progress. */
  readonly upload?: Upload | null;
  /** Absent for a deleted workspace's backups, which have nothing to restore into. */
  readonly onRestore?: (backup: Backup) => void;
  readonly onCreate: (backup: Backup) => void;
  readonly onDownload: (backup: Backup) => void;
  readonly onDelete: (backup: Backup) => void;
}

const PHASES: Readonly<Record<string, string>> = {
  preparing: "Recording the workspace's settings…",
  archiving: "Packing files, conversations and installed tools…",
  uploading: "Uploading to backup storage…",
};

const count = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`;

/** Versions newest first, each with what it holds and what can be done with it. */
export function BackupList({ backups, canRestore, upload, onRestore, onCreate, onDownload, onDelete }: Props) {
  return (
    <ul aria-label="Backup versions" className="space-y-3">
      {backups.map((backup) => (
        <li key={backup.id} className="flex flex-col gap-3 rounded-xl border border-zinc-200 p-4 sm:flex-row sm:items-center dark:border-zinc-800">
          <div className="min-w-0 flex-1 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <time dateTime={backup.createdAt} className="font-medium">{new Date(backup.createdAt).toLocaleString()}</time>
              {backup.uploaded && <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">Uploaded</span>}
              {backup.status === "FAILED" && <span className="rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700 dark:bg-rose-950 dark:text-rose-300">Failed</span>}
            </div>
            {backup.status === "RUNNING"
              ? <Running backup={backup} upload={upload?.backupId === backup.id ? upload : null} />
              : <Facts backup={backup} />}
          </div>
          <div className="flex shrink-0 flex-wrap gap-2">
            {backup.status === "COMPLETED" && <>
              <Button label="Download" icon={Download} variant="secondary" onClick={() => onDownload(backup)} />
              {onRestore && <Button label="Restore" icon={History} variant="secondary" disabled={!canRestore} onClick={() => onRestore(backup)} />}
              <Button label="Create new" icon={CopyPlus} variant="secondary" onClick={() => onCreate(backup)} />
            </>}
            {backup.status !== "RUNNING" && <Button label="Delete" icon={Trash2} variant="secondary" onClick={() => onDelete(backup)} />}
            {/* The workspace finishes its own backup; an upload is only ever ended from here. */}
            {backup.status === "RUNNING" && backup.uploaded && <Button label="Cancel upload" icon={X} variant="secondary" onClick={() => onDelete(backup)} />}
          </div>
        </li>
      ))}
    </ul>
  );
}

function Facts({ backup }: { readonly backup: Backup }) {
  if (backup.status === "FAILED") {
    return <p className="text-xs text-rose-600 dark:text-rose-400">{backup.error}</p>;
  }
  // An uploaded archive brings only the home, so the settings recorded beside it say nothing.
  const facts = backup.uploaded
    ? [backup.sizeBytes === null ? null : formatBytes(backup.sizeBytes), "files only"]
    : [
        backup.sizeBytes === null ? null : formatBytes(backup.sizeBytes),
        count(backup.sessions, "conversation"),
        count(backup.instructions.length, "instruction"),
        count(backup.tasks.length, "task"),
        count(backup.variables.length, "variable"),
        backup.model ?? "default model",
        backup.sessionTtlEnabled ? `sessions kept ${backup.sessionTtlHours}h` : "sessions kept forever",
      ];
  return <>
    <p className="text-xs text-zinc-500 dark:text-zinc-400">{facts.filter(Boolean).join(" · ")}</p>
    {!backup.uploaded && backup.secrets.length > 0 && (
      <p className="text-xs text-zinc-500 dark:text-zinc-400">Secrets to add again: <span className="font-mono">{backup.secrets.join(", ")}</span></p>
    )}
  </>;
}

function Running({ backup, upload }: { readonly backup: Backup; readonly upload: Upload | null }) {
  let progress = backup.progress;
  let detail = PHASES[backup.phase] ?? "Working…";
  if (upload) {
    progress = upload.total > 0 ? Math.min(99, Math.floor((upload.sent * 100) / upload.total)) : 0;
    detail = upload.finishing
      ? "Checking the archive arrived whole…"
      : `Uploading ${upload.fileName}: ${formatBytes(upload.sent)} of ${formatBytes(upload.total)}`;
  } else if (backup.uploaded) {
    detail = "Uploading from another tab, or interrupted";
  }
  return (
    <div className="space-y-1.5 pt-1">
      <ProgressBar label={backup.uploaded ? "Upload progress" : "Backup progress"} value={progress} />
      <div className="flex justify-between gap-3 text-xs text-zinc-500 dark:text-zinc-400">
        <span className="min-w-0 truncate">{detail}</span>
        <span className="tabular-nums">{progress}%</span>
      </div>
    </div>
  );
}

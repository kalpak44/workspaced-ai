import { CopyPlus, Download, History, Trash2 } from "lucide-react";
import type { Backup } from "../../hooks/useBackups";
import { formatBytes } from "../../lib/format";
import { Button, Spinner } from "../atoms";

interface Props {
  readonly backups: readonly Backup[];
  readonly canRestore: boolean;
  readonly onRestore: (backup: Backup) => void;
  readonly onCreate: (backup: Backup) => void;
  readonly onDownload: (backup: Backup) => void;
  readonly onDelete: (backup: Backup) => void;
}

const count = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`;

/** One workspace's versions, newest first, each with what it holds and what can be done with it. */
export function BackupList({ backups, canRestore, onRestore, onCreate, onDownload, onDelete }: Props) {
  return (
    <ul aria-label="Backup versions" className="space-y-3">
      {backups.map((backup) => {
        const completed = backup.status === "COMPLETED";
        const facts = [
          backup.sizeBytes === null ? null : formatBytes(backup.sizeBytes),
          count(backup.sessions, "conversation"),
          count(backup.instructions.length, "instruction"),
          count(backup.tasks.length, "task"),
          count(backup.variables.length, "variable"),
          backup.model ?? "default model",
          backup.sessionTtlEnabled ? `sessions kept ${backup.sessionTtlHours}h` : "sessions kept forever",
        ].filter(Boolean);
        return (
          <li key={backup.id} className="flex flex-col gap-3 rounded-xl border border-zinc-200 p-4 sm:flex-row sm:items-center dark:border-zinc-800">
            <div className="min-w-0 flex-1 space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <time dateTime={backup.createdAt} className="font-medium">{new Date(backup.createdAt).toLocaleString()}</time>
                {backup.status === "RUNNING" && <span className="flex items-center gap-1.5 text-xs text-zinc-500"><Spinner label="In progress" className="size-3" />{backup.progress}%</span>}
                {backup.status === "FAILED" && <span className="rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700 dark:bg-rose-950 dark:text-rose-300">Failed</span>}
              </div>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">{backup.status === "FAILED" ? backup.error : facts.join(" · ")}</p>
              {completed && backup.secrets.length > 0 && (
                <p className="text-xs text-zinc-500 dark:text-zinc-400">Secrets to add again: <span className="font-mono">{backup.secrets.join(", ")}</span></p>
              )}
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              {completed && <>
                <Button label="Download" icon={Download} variant="secondary" onClick={() => onDownload(backup)} />
                {canRestore && <Button label="Restore" icon={History} variant="secondary" onClick={() => onRestore(backup)} />}
                <Button label="Create new" icon={CopyPlus} onClick={() => onCreate(backup)} />
              </>}
              {backup.status !== "RUNNING" && <Button label="Delete" icon={Trash2} variant="secondary" onClick={() => onDelete(backup)} />}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

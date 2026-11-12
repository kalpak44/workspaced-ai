import { useEffect, useRef, useState } from "react";
import { type Backup, getBackup, startBackup } from "../api/backupsApi";
import { ApiError } from "../api/http";

export type { Backup } from "../api/backupsApi";

const POLL_MS = 1000;

/**
 * One backup being made, from the click to COMPLETED or FAILED. The API keeps the progress, so
 * closing the dialog does not stop the backup; it only stops this hook asking.
 */
export function useBackupRun(workspaceId: string) {
  const [backup, setBackup] = useState<Backup | null>(null);
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    // StrictMode mounts twice in development; a second POST would be refused as a duplicate.
    if (started.current) return undefined;
    started.current = true;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = (id: string) => {
      timer = setTimeout(() => {
        getBackup(id).then(
          (next) => {
            if (!active) return;
            setBackup(next);
            if (next.status === "RUNNING") poll(id);
          },
          () => active && poll(id),
        );
      }, POLL_MS);
    };
    startBackup(workspaceId).then(
      (first) => {
        if (!active) return;
        setBackup(first);
        poll(first.id);
      },
      (failure: unknown) => {
        if (!active) return;
        setError(messageFor(failure));
      },
    );
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [workspaceId]);

  return { backup, error };
}

function messageFor(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 409) {
    return "This workspace is already being backed up, or it needs a restart to pick up backups.";
  }
  if (failure instanceof ApiError && failure.status === 503) {
    return "The workspace is not running, or backups are not set up.";
  }
  return "Could not start the backup.";
}

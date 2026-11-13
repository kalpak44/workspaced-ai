import { useEffect, useState } from "react";
import { getRestoreProgress, type RestoreProgress } from "../api/backupsApi";
import { formatBytes } from "../lib/format";

export type { RestoreProgress } from "../api/backupsApi";

const POLL_MS = 2000;

/** Asked for only while the workspace is starting, which is the only time it can be restoring. */
export function useRestoreProgress(workspaceId: string, starting: boolean): RestoreProgress | null {
  const [progress, setProgress] = useState<RestoreProgress | null>(null);

  useEffect(() => {
    if (!starting) {
      setProgress(null);
      return undefined;
    }
    let active = true;
    const read = () =>
      getRestoreProgress(workspaceId).then(
        (next) => active && setProgress(next),
        () => {},
      );
    void read();
    const timer = setInterval(() => void read(), POLL_MS);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [workspaceId, starting]);

  return progress;
}

/** What a restore is doing, in words; the agent starting is all that is left once it is done. */
export function describeRestore({ phase, done, total }: RestoreProgress): string {
  switch (phase) {
    case "downloading":
      return total > 0 ? `Downloading the backup: ${formatBytes(done)} of ${formatBytes(total)}` : "Downloading the backup…";
    case "checking":
      return "Checking the archive before anything is replaced…";
    case "extracting":
      return `Restoring files: ${done.toLocaleString()} of ${total.toLocaleString()}`;
    default:
      return "Starting the agent…";
  }
}

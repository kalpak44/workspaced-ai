import { useEffect, useState } from "react";
import { getRestoreProgress, type RestoreProgress } from "../api/backupsApi";

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

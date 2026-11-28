import { useEffect, useState } from "react";
import { readWorkspacedUsage, type WorkspacedUsage as Usage } from "../api/aiConnectorsApi";
export function useWorkspacedUsage(connectorId: string) {
  const [rows, setRows] = useState<Usage[] | null>(null);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setBusy(true); setError(false);
    readWorkspacedUsage(abort.signal).then(value => {
      if (!abort.signal.aborted) setRows(value.filter(row => row.connectorId === connectorId));
    }).catch(() => { if (!abort.signal.aborted) setError(true); })
      .finally(() => { if (!abort.signal.aborted) setBusy(false); });
    return () => abort.abort();
  }, [connectorId, revision]);
  return { rows, error, busy, refresh: () => setRevision(value => value + 1) };
}

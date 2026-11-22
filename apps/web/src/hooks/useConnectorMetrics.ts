import { useEffect, useState } from "react";
import { readConnectorMetrics, type ConnectorMetrics } from "../api/aiConnectorsApi";
import { ApiError } from "../api/http";
export function useConnectorMetrics(id: string) {
  const [snapshot, setSnapshot] = useState<{ id: string; metrics: ConnectorMetrics; at: Date } | null>(null);
  const [failure, setFailure] = useState<{ id: string; message: string } | null>(null);
  const [pendingId, setPendingId] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!id) return;
    const abort = new AbortController();
    setPendingId(id); setFailure(null);
    readConnectorMetrics(id, abort.signal).then(metrics => {
      if (!abort.signal.aborted) setSnapshot({ id, metrics, at: new Date() });
    }).catch(cause => {
      if (abort.signal.aborted) return;
      const message = cause instanceof ApiError && cause.status === 422
        ? "OpenRouter rejected this key. It may have expired or been revoked."
        : cause instanceof ApiError && cause.status === 429
          ? "OpenRouter is limiting usage checks. Wait a moment, then refresh."
          : "Could not load usage from OpenRouter. Please refresh to try again.";
      setFailure({ id, message });
    }).finally(() => { if (!abort.signal.aborted) setPendingId(""); });
    return () => abort.abort();
  }, [id, revision]);
  return { metrics: snapshot?.id === id ? snapshot.metrics : null, updatedAt: snapshot?.id === id ? snapshot.at : null,
    error: failure?.id === id ? failure.message : "", loading: !!id && pendingId === id, refresh: () => setRevision(value => value + 1) };
}

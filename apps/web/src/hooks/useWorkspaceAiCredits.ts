import { useCallback, useEffect, useState } from "react";
import { send } from "../api/http";
import { workspaceUrl } from "../api/workspacesApi";
import { useAccount } from "./useAccount";

export interface WorkspaceAiCredits {
  readonly remainingUsd: number | null;
  readonly warningThresholdUsd: number;
  readonly low: boolean;
  readonly exhausted: boolean;
  readonly trial: boolean;
}

/** Poll shared credit availability; keep the last known status if a refresh fails. */
export function useWorkspaceAiCredits(id: string | null) {
  const { account } = useAccount();
  const [snapshot, setSnapshot] = useState<{ id: string; value: WorkspaceAiCredits } | null>(null);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision(value => value + 1), []);
  useEffect(() => {
    if (!id) return;
    const abort = new AbortController();
    send(`${workspaceUrl(id)}/ai-credits`, { signal: abort.signal }).then(response => response.json())
      .then((value: WorkspaceAiCredits) => { if (!abort.signal.aborted) setSnapshot({ id, value }); })
      .catch(() => undefined);
    return () => abort.abort();
  }, [id, revision, account?.aiCreditWarningUsd]);
  useEffect(() => {
    if (!id) return;
    const interval = setInterval(refresh, 30_000);
    globalThis.addEventListener("focus", refresh);
    return () => { clearInterval(interval); globalThis.removeEventListener("focus", refresh); };
  }, [id, refresh]);
  const status = snapshot?.id === id ? snapshot.value : null;
  return { status, blocked: status?.exhausted === true, refresh };
}

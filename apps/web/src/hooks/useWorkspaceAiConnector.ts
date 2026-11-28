import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { readProviderBindingStatus, selectAiConnector, type ProviderBindingStatus } from "../api/aiConnectorsApi";
import type { Workspace } from "./useWorkspaces";

/** Selection is staged; only fresh server status can identify the active connector. */
export function useWorkspaceAiConnector(workspace: Workspace, onChanged: (changes: Partial<Workspace>) => void) {
  const [selected, setSelected] = useState("");
  const [status, setStatus] = useState<ProviderBindingStatus | null>(null);
  const [reading, setReading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const latest = useRef({ workspace, onChanged });
  latest.current = { workspace, onChanged };
  const sequence = useRef(0);
  const refresh = useCallback(async () => {
    const request = ++sequence.current;
    const result = await readProviderBindingStatus(workspace.id);
    if (request !== sequence.current || latest.current.workspace.id !== workspace.id) return;
    setStatus(result);
    setSelected(current => result.choices.some(choice => choice.id === current) ? current : result.pending?.id ?? result.active?.id ?? result.choices[0]?.id ?? "");
    const changes: Partial<Workspace> = {
      ...(result.restartRequired !== latest.current.workspace.restartRequired ? { restartRequired: result.restartRequired } : {}),
      ...(result.active && result.active.provider !== latest.current.workspace.modelProvider ? { modelProvider: result.active.provider } : {}),
    };
    if (Object.keys(changes).length) latest.current.onChanged(changes);
  }, [workspace.id]);
  useEffect(() => {
    let mounted = true;
    const read = () => { if (mounted) void refresh().catch(() => { if (mounted) setError("Could not verify the connector state. Please try again."); }).finally(() => { if (mounted) setReading(false); }); };
    read();
    const interval = setInterval(read, 3000);
    globalThis.addEventListener("focus", read);
    return () => { mounted = false; sequence.current++; clearInterval(interval); globalThis.removeEventListener("focus", read); };
  }, [refresh]);
  const save = async () => {
    setBusy(true); setError("");
    try { await selectAiConnector(workspace.id, selected); await refresh(); }
    catch (cause) {
      setError(cause instanceof ApiError && cause.status === 422 ? "The provider rejected this key. It may have expired or been revoked."
        : cause instanceof ApiError && cause.status === 409 ? "The connector change cannot be staged right now. Wait for the current restart or check the connector."
        : "Could not update or verify the pending connector. Please check again.");
    }
    finally { setBusy(false); }
  };
  return { selected, setSelected, current: status?.pending?.id ?? status?.active?.id ?? "", status, reading, busy, error, save, refresh };
}

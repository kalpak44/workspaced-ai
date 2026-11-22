import { useEffect, useState } from "react";
import { ApiError } from "../api/http";
import { selectedAiConnector, selectAiConnector } from "../api/aiConnectorsApi";
import type { Workspace } from "./useWorkspaces";

/** The provider key selected under workspace Secrets, applied after a restart. */
export function useWorkspaceAiConnector(workspace: Workspace, onChanged: (changes: Partial<Workspace>) => void) {
  const [selected, setSelected] = useState("");
  const [current, setCurrent] = useState("");
  const [reading, setReading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { let active = true; selectedAiConnector(workspace.id).then(connector => { if (active) { setSelected(connector?.id ?? ""); setCurrent(connector?.id ?? ""); } }).catch(() => { if (active) setError("Could not load the current connector."); }).finally(() => { if (active) setReading(false); }); return () => { active = false; }; }, [workspace.id]);
  const save = async () => {
    setBusy(true); setError("");
    try { await selectAiConnector(workspace.id, selected); setCurrent(selected); onChanged({ restartRequired: true }); }
    catch (cause) {
      setError(cause instanceof ApiError && cause.status === 422 ? "OpenRouter rejected this key. It may have expired or been revoked."
        : cause instanceof ApiError && cause.status === 409 ? "The workspace is not ready to change its key. Try again after it starts."
        : "Could not update the workspace's provider key. Please try again.");
    }
    finally { setBusy(false); }
  };
  return { selected, setSelected, current, reading, busy, error, save };
}

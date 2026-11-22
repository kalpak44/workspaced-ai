import { useEffect, useState } from "react";
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
    catch { setError("Could not change the connector. Check that its key is valid and try again."); }
    finally { setBusy(false); }
  };
  return { selected, setSelected, current, reading, busy, error, save };
}

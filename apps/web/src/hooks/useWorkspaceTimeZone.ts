import { useEffect, useState } from "react";
import { ApiError } from "../api/http";
import { setWorkspaceTimeZone, type Workspace } from "../api/workspacesApi";

/** The workspace's time zone as edited here, saved on its own. */
export function useWorkspaceTimeZone(workspace: Workspace, onChanged: (changes: Partial<Workspace>) => void) {
  const [zone, setZone] = useState(workspace.timeZone);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => setZone(workspace.timeZone), [workspace.id, workspace.timeZone]);

  async function save() {
    setSaving(true);
    setNotice("");
    try {
      const saved = await setWorkspaceTimeZone(workspace.id, zone);
      onChanged({ timeZone: saved.timeZone, restartRequired: saved.restartRequired });
      setNotice("Time zone saved.");
    } catch (failure) {
      setNotice(failure instanceof ApiError && failure.status === 409
        ? "The workspace has no VM yet. Try again once it has started."
        : "Could not save the time zone. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  return {
    zone,
    setZone: (next: string) => { setZone(next); setNotice(""); },
    changed: zone !== workspace.timeZone,
    saving,
    notice,
    save,
  };
}

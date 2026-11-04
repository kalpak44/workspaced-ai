import { useEffect, useRef, useState } from "react";
import { getSessionTtl, setSessionTtl, type SessionTtlSettings } from "../api/settingsApi";

export const SESSION_TTL_OPTIONS = [
  { hours: 1, label: "1 hour" },
  { hours: 6, label: "6 hours" },
  { hours: 24, label: "1 day" },
  { hours: 72, label: "3 days" },
  { hours: 168, label: "7 days" },
  { hours: 720, label: "30 days" },
] as const;

/** Workspace retention preferences persisted by the API. */
export function useSessionTtlSettings(workspaceId: string) {
  const [settings, setSettings] = useState<SessionTtlSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    const current = ++generation.current;
    setSettings(null);
    setLoading(true);
    setError(null);
    setSaving(false);
    getSessionTtl(workspaceId)
      .then((value) => { if (generation.current === current) setSettings(value); })
      .catch(() => { if (generation.current === current) setError("Could not load session settings."); })
      .finally(() => { if (generation.current === current) setLoading(false); });
    return () => { generation.current++; };
  }, [workspaceId, attempt]);

  async function update(value: SessionTtlSettings): Promise<boolean> {
    const current = generation.current;
    setSaving(true);
    try {
      const saved = await setSessionTtl(workspaceId, value);
      if (generation.current !== current) return false;
      setSettings(saved);
      return true;
    } catch {
      return false;
    } finally {
      if (generation.current === current) setSaving(false);
    }
  }
  return { settings, loading, saving, error, update, retry: () => setAttempt((value) => value + 1) };
}

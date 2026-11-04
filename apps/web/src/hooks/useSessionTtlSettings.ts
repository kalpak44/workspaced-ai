import { useMemo, useSyncExternalStore } from "react";
import { readStored } from "../lib/storage";

const CHANGED = "workspaced:session-ttl-settings-changed";
export const SESSION_TTL_OPTIONS = [
  { hours: 1, label: "1 hour" },
  { hours: 6, label: "6 hours" },
  { hours: 24, label: "1 day" },
  { hours: 72, label: "3 days" },
  { hours: 168, label: "7 days" },
  { hours: 720, label: "30 days" },
] as const;

interface SessionTtlSettings {
  readonly sessionTtlEnabled: boolean;
  readonly sessionTtlHours: number;
}
const DEFAULTS: SessionTtlSettings = { sessionTtlEnabled: false, sessionTtlHours: 168 };

function subscribe(listener: () => void) {
  globalThis.addEventListener(CHANGED, listener);
  globalThis.addEventListener("storage", listener);
  return () => {
    globalThis.removeEventListener(CHANGED, listener);
    globalThis.removeEventListener("storage", listener);
  };
}

/** Browser-only preferences scoped to one workspace; deletion is not enforced yet. */
export function useSessionTtlSettings(workspaceId: string) {
  const key = `workspaced:session-ttl:${workspaceId}`;
  const raw = useSyncExternalStore(subscribe, () => readStored(key));
  const settings = useMemo<SessionTtlSettings>(() => {
    try {
      const parsed = JSON.parse(raw ?? "{}");
      return {
        sessionTtlEnabled: typeof parsed?.sessionTtlEnabled === "boolean" ? parsed.sessionTtlEnabled : DEFAULTS.sessionTtlEnabled,
        sessionTtlHours: SESSION_TTL_OPTIONS.some(({ hours }) => hours === parsed?.sessionTtlHours) ? parsed.sessionTtlHours : DEFAULTS.sessionTtlHours,
      };
    } catch {
      return DEFAULTS;
    }
  }, [raw]);

  function update(patch: Partial<SessionTtlSettings>): boolean {
    try {
      globalThis.localStorage.setItem(key, JSON.stringify({ ...settings, ...patch }));
      globalThis.dispatchEvent(new Event(CHANGED));
      return true;
    } catch {
      return false;
    }
  }
  return { settings, update };
}

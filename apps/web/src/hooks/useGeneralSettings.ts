import { useMemo, useSyncExternalStore } from "react";
import { readStored } from "../lib/storage";

const KEY = "workspaced:general-settings";
const CHANGED = "workspaced:general-settings-changed";
interface GeneralSettings {
  readonly firstName: string;
  readonly lastName: string;
  readonly avatar: string;
  readonly instructions: string;
}
const EMPTY: GeneralSettings = { firstName: "", lastName: "", avatar: "", instructions: "" };

function subscribe(listener: () => void) {
  globalThis.addEventListener(CHANGED, listener);
  globalThis.addEventListener("storage", listener);
  return () => {
    globalThis.removeEventListener(CHANGED, listener);
    globalThis.removeEventListener("storage", listener);
  };
}

/** Browser-only preferences. Passwords never enter this store. */
export function useGeneralSettings() {
  const raw = useSyncExternalStore(subscribe, () => readStored(KEY));
  const settings = useMemo<GeneralSettings>(() => {
    try {
      const parsed = JSON.parse(raw ?? "{}");
      return Object.fromEntries(Object.entries(EMPTY).map(([key, fallback]) => [
        key, typeof parsed?.[key] === "string" ? parsed[key] : fallback,
      ])) as unknown as GeneralSettings;
    } catch {
      return EMPTY;
    }
  }, [raw]);

  function update(patch: Partial<GeneralSettings>): boolean {
    try {
      globalThis.localStorage.setItem(KEY, JSON.stringify({ ...settings, ...patch }));
      globalThis.dispatchEvent(new Event(CHANGED));
      return true;
    } catch {
      return false;
    }
  }
  return { settings, update };
}

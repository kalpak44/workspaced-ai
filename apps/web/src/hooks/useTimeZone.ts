import { useSyncExternalStore } from "react";
import { readStored, writeStored } from "../lib/storage";

const KEY = "workspaced.timeZone";
// Writes in one tab or component announce themselves, so every reader follows at once.
const CHANGED = "workspaced:time-zone";

export const BROWSER_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
export const TIME_ZONES: readonly string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

function subscribe(onChange: () => void): () => void {
  globalThis.addEventListener(CHANGED, onChange);
  globalThis.addEventListener("storage", onChange);
  return () => {
    globalThis.removeEventListener(CHANGED, onChange);
    globalThis.removeEventListener("storage", onChange);
  };
}

/** The chosen zone, or null to follow the browser's. Kept in this browser until the API stores it. */
export function useTimeZone(): [string | null, (zone: string | null) => void] {
  const stored = useSyncExternalStore(subscribe, () => readStored(KEY), () => null);
  const zone = stored && TIME_ZONES.includes(stored) ? stored : null;
  const setZone = (next: string | null) => {
    writeStored(KEY, next ?? "");
    globalThis.dispatchEvent(new Event(CHANGED));
  };
  return [zone, setZone];
}

/** "GMT+02:00" for a zone at this moment, so a list of zones can be read by offset. */
export function zoneOffset(zone: string, at = new Date()): string {
  const part = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "longOffset" })
    .formatToParts(at)
    .find((p) => p.type === "timeZoneName")?.value;
  return part === "GMT" ? "GMT+00:00" : (part ?? "");
}

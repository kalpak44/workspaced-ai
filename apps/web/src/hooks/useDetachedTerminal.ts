import { useSyncExternalStore } from "react";
import { deleteTerminal } from "../api/terminalsApi";
import { readStored, writeStored } from "../lib/storage";

const KEY = "workspaced.terminal.detached";
const CHANGED = "workspaced:terminal-detached";

/** A terminal taken out of its page into a window that stays open on every page. */
export interface DetachedTerminal {
  readonly workspaceId: string;
  readonly workspaceName: string;
  readonly terminalId: string;
  readonly title: string;
}

function read(): DetachedTerminal | null {
  try {
    const value = JSON.parse(readStored(KEY) ?? "null") as DetachedTerminal | null;
    return value && typeof value.workspaceId === "string" && typeof value.terminalId === "string" ? value : null;
  } catch {
    return null;
  }
}

// Parsed once per change, so every render sees the same object.
let current = read();

function subscribe(onChange: () => void): () => void {
  globalThis.addEventListener(CHANGED, onChange);
  return () => globalThis.removeEventListener(CHANGED, onChange);
}

/** Opens `terminal` in the floating window, replacing the one there; null closes it. */
export function setDetachedTerminal(terminal: DetachedTerminal | null): void {
  current = terminal;
  writeStored(KEY, JSON.stringify(terminal));
  globalThis.dispatchEvent(new Event(CHANGED));
}

/** The terminal in the floating window, kept across pages and reloads; null when there is none. */
export function useDetachedTerminal(): DetachedTerminal | null {
  return useSyncExternalStore(subscribe, () => current);
}

/** Closes the floating window by deleting its shell; false, keeping the window, when that fails. */
export async function closeDetachedTerminal(): Promise<boolean> {
  const terminal = current;
  if (!terminal) return true;
  try {
    await deleteTerminal(terminal.workspaceId, terminal.terminalId);
  } catch {
    return false;
  }
  if (current === terminal) setDetachedTerminal(null);
  return true;
}

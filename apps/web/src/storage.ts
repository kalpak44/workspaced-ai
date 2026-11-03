// Storage can be absent or throw (private windows, blocked site data), and the app has
// to work without it — so every access degrades to "nothing stored".
export function readStored(key: string): string | null {
  try {
    return globalThis.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string): void {
  try {
    globalThis.localStorage.setItem(key, value);
  } catch {
    // Not persisted; the in-memory state is still correct for this visit.
  }
}

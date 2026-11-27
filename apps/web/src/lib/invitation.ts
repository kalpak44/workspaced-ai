import { readStored, writeStored } from "./storage";

const KEY = "workspaced:invitation";
const PATH = "/invitation";

/**
 * Keeps the code from an invitation link (`/invitation#<code>`) until someone is signed in to
 * accept it. Signing in or up goes through Auth0 and comes back to `/`, which loses the URL, so
 * the code waits in storage instead. The fragment never reaches a server log.
 */
export function captureInvitation(): void {
  if (globalThis.location.pathname !== PATH) return;
  const code = decodeURIComponent(globalThis.location.hash.slice(1));
  if (/^[A-Za-z0-9_-]{1,100}$/.test(code)) writeStored(KEY, code);
  globalThis.history.replaceState(globalThis.history.state, "", "/");
}

/** Whether an invitation is waiting for a sign-in. */
export function hasPendingInvitation(): boolean {
  return readStored(KEY) !== null;
}

/** The waiting code, removed as it is read so a failed accept is not retried on every load. */
export function takeInvitation(): string | null {
  const code = readStored(KEY);
  if (code !== null) {
    try {
      globalThis.localStorage.removeItem(KEY);
    } catch {
      // Storage is unusable; nothing was kept there either.
    }
  }
  return code;
}

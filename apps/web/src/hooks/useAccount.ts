import { useEffect, useSyncExternalStore } from "react";
import * as api from "../api/accountApi";
import { ApiError, UNAUTHORIZED } from "../api/http";

export type { Account, Profile } from "../api/accountApi";

export type AccountState =
  | { readonly status: "checking" }
  | { readonly status: "signed-out"; readonly unreachable?: boolean }
  | { readonly status: "signed-in"; readonly account: api.Account };

// One account for the whole page, so the menu, the settings and the app shell agree on it.
let state: AccountState = { status: "checking" };
let loading: Promise<void> | null = null;
const listeners = new Set<() => void>();

function set(next: AccountState) {
  state = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function load() {
  loading ??= api.fetchAccount().then(
    (account) => set(account ? { status: "signed-in", account } : { status: "signed-out" }),
    () => set({ status: "signed-out", unreachable: true }),
  );
  return loading;
}

globalThis.addEventListener(UNAUTHORIZED, () => {
  if (state.status === "signed-in") set({ status: "signed-out" });
});

/** Forgets the account, so the next render checks the API again; for tests. */
export function resetAccount() {
  state = { status: "checking" };
  loading = null;
}

/** The signed-in account, and everything that changes it. */
export function useAccount() {
  const current = useSyncExternalStore(subscribe, () => state);
  useEffect(() => {
    if (state.status === "checking") void load();
  }, []);

  return {
    state: current,
    account: current.status === "signed-in" ? current.account : null,
    /** False for a wrong username or password; any other failure throws. */
    async signIn(username: string, password: string): Promise<boolean> {
      try {
        set({ status: "signed-in", account: await api.login(username, password) });
        return true;
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return false;
        throw error;
      }
    },
    async signOut() {
      try {
        await api.logout();
      } finally {
        set({ status: "signed-out" });
      }
    },
    async saveProfile(profile: api.Profile) {
      set({ status: "signed-in", account: await api.updateProfile(profile) });
    },
    /** False when the current password is wrong; any other failure throws. */
    async changePassword(currentPassword: string, newPassword: string): Promise<boolean> {
      try {
        await api.changePassword(currentPassword, newPassword);
        return true;
      } catch (error) {
        if (error instanceof ApiError && error.status === 422) return false;
        throw error;
      }
    },
  };
}

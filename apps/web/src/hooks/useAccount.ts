import { useEffect, useSyncExternalStore } from "react";
import * as api from "../api/accountApi";
import { UNAUTHORIZED } from "../api/http";

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
    /** Leaves for Auth0's login page; the API brings the browser back signed in. */
    signIn: api.signIn,
    /** Leaves for Auth0's sign-up form; the account can sign in once its email is verified. */
    signUp: api.signUp,
    /**
     * Signed out here first, so nothing renders as signed in while Auth0's logout loads. A failed
     * API call still signs out here, but leaves no logout URL to follow.
     */
    async signOut() {
      const ended = await api.logout().then(
        () => true,
        () => false,
      );
      set({ status: "signed-out" });
      if (ended) api.endAuth0Session();
    },
    async saveProfile(profile: api.Profile) {
      set({ status: "signed-in", account: await api.updateProfile(profile) });
    },
    requestPasswordReset: api.requestPasswordReset,
    enrollMfa: api.enrollMfa,
  };
}

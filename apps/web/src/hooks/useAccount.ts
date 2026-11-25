import { useEffect, useSyncExternalStore } from "react";
import * as api from "../api/accountApi";
import { UNAUTHORIZED } from "../api/http";
import { adoptTourProgress, saveTourWith } from "../lib/tour";

export type { Account, MailPreferences, Profile, ResendResult } from "../api/accountApi";

/**
 * Why no one is signed in, when it is not simply a visitor arriving: the account check could
 * not reach the API, or a sign-out is leaving for Auth0 or failed on the way.
 */
export type SignedOutReason = "unreachable" | "signing-out" | "sign-out-failed";

export type AccountState =
  | { readonly status: "checking" }
  | { readonly status: "signed-out"; readonly reason?: SignedOutReason }
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
    (account) => {
      if (account) adoptTourProgress(account.tour ?? null);
      set(account ? { status: "signed-in", account } : { status: "signed-out" });
    },
    () => set({ status: "signed-out", reason: "unreachable" }),
  );
  return loading;
}

saveTourWith(api.saveTour);

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
     * API call still signs out here, but does not go on to end Auth0's session.
     */
    async signOut() {
      const ended = await api.logout().then(
        () => true,
        () => false,
      );
      set({ status: "signed-out", reason: ended ? "signing-out" : "sign-out-failed" });
      if (ended) api.endAuth0Session();
    },
    resendVerificationEmail: api.resendVerificationEmail,
    async saveProfile(profile: api.Profile) {
      set({ status: "signed-in", account: await api.updateProfile(profile) });
    },
    async saveMailPreferences(preferences: Partial<api.MailPreferences>) {
      set({ status: "signed-in", account: await api.updateMailPreferences(preferences) });
    },
    requestPasswordReset: api.requestPasswordReset,
    enrollMfa: api.enrollMfa,
  };
}

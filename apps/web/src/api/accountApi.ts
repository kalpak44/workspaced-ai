import type { TourProgress } from "../lib/tour";
import { ApiError, send } from "./http";

const ACCOUNT_URL = "/api/v1/account";

/** The signed-in user. The sign-in itself is an HttpOnly cookie this code never sees. */
export interface Account {
  readonly id: string;
  readonly email: string;
  readonly firstName: string;
  readonly lastName: string;
  /** Auth0's picture of the user, Gravatar or their initials; null for the default. */
  readonly avatar: string | null;
  /** An admin also manages the platform's mail. */
  readonly role: Role;
  /** Whether the account can still start its one free demo workspace. */
  readonly demoAvailable?: boolean;
  /** Where the guided tour is, so any browser resumes it; null while none is under way. */
  readonly tour?: TourProgress | null;
  readonly mail?: MailPreferences;
  readonly aiCreditWarningUsd?: number;
}

/** The platform mail a user may turn off; pauses, deletions and failed top-ups are always mailed. */
export interface MailPreferences {
  /** The balance reminders mailed before a workspace is paused; they stay in the app either way. */
  readonly balanceReminders: boolean;
  /** The occasional tips: trying the free trial, what to do after it, inviting friends, a balance left unused. */
  readonly tips: boolean;
}

export type Role = "USER" | "ADMIN";

/** The names are all a user edits here; Auth0 owns the email and the picture. */
export interface Profile {
  readonly firstName: string;
  readonly lastName: string;
}

/** Who is signed in, or null when no one is. */
export async function fetchAccount(): Promise<Account | null> {
  try {
    return await (await send(ACCOUNT_URL)).json();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

// Spring's OAuth2 login entry point: it sends the browser on to Auth0 and back to / with the cookie set.
const SIGN_IN_URL = "/api/v1/oauth2/authorization/auth0";

/** Leaves the app for Auth0; an object so tests can stub it, since jsdom cannot navigate. */
export const browser = {
  open(url: string): void {
    globalThis.location.assign(url);
  },
};

/**
 * The utm_ parameters and referral code this page was opened with, passed on so the API records
 * where a sign-up came from and who invited it.
 */
function attribution(): string {
  const params = new URLSearchParams(globalThis.location.search);
  const kept = new URLSearchParams();
  for (const name of ["utm_source", "utm_campaign", "ref"]) {
    const value = params.get(name);
    if (value) kept.set(name, value);
  }
  return kept.size ? `&${kept}` : "";
}

/** A full-page navigation, not a fetch: Auth0's login page has to be on screen. */
export function signIn(): void {
  const forwarded = attribution();
  browser.open(forwarded ? `${SIGN_IN_URL}?${forwarded.slice(1)}` : SIGN_IN_URL);
}

/** The same entry point, opened on Auth0's sign-up form instead of its login form. */
export function signUp(): void {
  browser.open(`${SIGN_IN_URL}?signup${attribution()}`);
}

/** A sign-in that sets up a second factor first; every later sign-in asks for it. */
export function enrollMfa(): void {
  browser.open(`${SIGN_IN_URL}?mfa=enroll`);
}

export type ResendResult = "sent" | "wait" | "expired";

/**
 * Sends the verification email again to the address that just signed up. "wait" is the API's
 * rate limit; "expired" means its short-lived proof of who signed up is gone, so only a new
 * sign-in can ask for another email.
 */
export async function resendVerificationEmail(): Promise<ResendResult> {
  try {
    await send("/api/v1/auth/verification-email", { method: "POST" });
    return "sent";
  } catch (error) {
    if (error instanceof ApiError && error.status === 429) return "wait";
    if (error instanceof ApiError && error.status === 401) return "expired";
    throw error;
  }
}

/** Ends the API session; Auth0's is ended by endAuth0Session. */
export async function logout(): Promise<void> {
  await send("/api/v1/auth/logout", { method: "POST" });
}

/**
 * The API redirects this fixed path to Auth0's logout, so the browser never navigates to an
 * address it was handed. Without it the next sign-in would pass without a password.
 */
export function endAuth0Session(): void {
  browser.open("/api/v1/auth/end-session");
}

export async function updateProfile(profile: Profile): Promise<Account> {
  const body = JSON.stringify({ firstName: profile.firstName, lastName: profile.lastName });
  return (await send(`${ACCOUNT_URL}/profile`, { method: "PUT", body })).json();
}

/** Changes only the preferences given; the API keeps the others. */
export async function updateMailPreferences(preferences: Partial<MailPreferences>): Promise<Account> {
  const body = JSON.stringify(preferences);
  return (await send(`${ACCOUNT_URL}/mail-preferences`, { method: "PUT", body })).json();
}

/** Saves where the guided tour is; null ends it. */
export async function saveTour(progress: TourProgress | null): Promise<void> {
  await send(`${ACCOUNT_URL}/tour`, progress
    ? { method: "PUT", body: JSON.stringify({ ...progress, paused: progress.paused ?? false }) }
    : { method: "DELETE" }, { retry: "idempotent" });
}

/** Auth0 emails the signed-in user a link to set a new password. */
export async function requestPasswordReset(): Promise<void> {
  await send(`${ACCOUNT_URL}/password-reset`, { method: "POST" });
}

export async function updateAiCreditPreferences(warningThresholdUsd: number): Promise<Account> {
  return (await send(`${ACCOUNT_URL}/ai-credit-preferences`, { method: "PUT", body: JSON.stringify({ warningThresholdUsd }) })).json();
}

import { ApiError, send } from "./http";

const ACCOUNT_URL = "/api/v1/account";

/** The signed-in user. The sign-in itself is an HttpOnly cookie this code never sees. */
export interface Account {
  readonly id: string;
  readonly email: string;
  readonly firstName: string;
  readonly lastName: string;
  /** A data: URL of the picture; null for the default. */
  readonly avatar: string | null;
  /** An admin also manages the platform's mail. */
  readonly role: Role;
}

export type Role = "USER" | "ADMIN";

export interface Profile {
  readonly firstName: string;
  readonly lastName: string;
  readonly avatar: string | null;
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

/** A full-page navigation, not a fetch: Auth0's login page has to be on screen. */
export function signIn(): void {
  browser.open(SIGN_IN_URL);
}

/** The same entry point, opened on Auth0's sign-up form instead of its login form. */
export function signUp(): void {
  browser.open(`${SIGN_IN_URL}?signup`);
}

/** A sign-in that sets up a second factor first; every later sign-in asks for it. */
export function enrollMfa(): void {
  browser.open(`${SIGN_IN_URL}?mfa=enroll`);
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
  const body = JSON.stringify({ ...profile, avatar: profile.avatar ?? "" });
  return (await send(`${ACCOUNT_URL}/profile`, { method: "PUT", body })).json();
}

/** Auth0 emails the signed-in user a link to set a new password. */
export async function requestPasswordReset(): Promise<void> {
  await send(`${ACCOUNT_URL}/password-reset`, { method: "POST" });
}

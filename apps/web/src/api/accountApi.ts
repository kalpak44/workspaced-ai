import { ApiError, send } from "./http";

const ACCOUNT_URL = "/api/v1/account";

/** The signed-in user. The sign-in itself is an HttpOnly cookie this code never sees. */
export interface Account {
  readonly id: string;
  readonly username: string;
  readonly firstName: string;
  readonly lastName: string;
  /** A data: URL of the picture; null for the default. */
  readonly avatar: string | null;
}

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

/** The API sets the cookie; the token in the body is for clients without one. */
export async function login(username: string, password: string): Promise<Account> {
  const body = JSON.stringify({ username, password });
  return (await (await send("/api/v1/auth/login", { method: "POST", body })).json()).account;
}

export async function logout(): Promise<void> {
  await send("/api/v1/auth/logout", { method: "POST" });
}

export async function updateProfile(profile: Profile): Promise<Account> {
  const body = JSON.stringify({ ...profile, avatar: profile.avatar ?? "" });
  return (await send(`${ACCOUNT_URL}/profile`, { method: "PUT", body })).json();
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  const body = JSON.stringify({ currentPassword, newPassword });
  await send(`${ACCOUNT_URL}/password`, { method: "PUT", body });
}

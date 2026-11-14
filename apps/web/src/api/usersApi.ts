import type { Role } from "./accountApi";
import { send } from "./http";

const USERS_URL = "/api/v1/users";

/** Someone who can sign in to this installation. Only an admin may list, create or delete them. */
export interface User {
  readonly id: string;
  readonly username: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly avatar: string | null;
  readonly role: Role;
}

export interface NewUser {
  readonly username: string;
  readonly password: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly role: Role;
}

/** Spring's paged response: one page of users, and where it sits among them all. */
export interface UserPage {
  readonly content: User[];
  readonly page: {
    readonly size: number;
    readonly number: number;
    readonly totalElements: number;
    readonly totalPages: number;
  };
}

/** `sort` is Spring's "field,direction", one per field, most significant first. */
export async function listUsers(page: number, size: number, sort: readonly string[]): Promise<UserPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  sort.forEach((order) => query.append("sort", order));
  return (await send(`${USERS_URL}?${query}`)).json();
}

export async function createUser(user: NewUser): Promise<User> {
  return (await send(USERS_URL, { method: "POST", body: JSON.stringify(user) })).json();
}

/** Applies to their next request. The API refuses to change the signed-in user's own role. */
export async function changeUserRole(id: string, role: Role): Promise<User> {
  return (await send(`${USERS_URL}/${encodeURIComponent(id)}/role`, { method: "PUT", body: JSON.stringify({ role }) })).json();
}

/** The API refuses to delete the signed-in user. */
export async function deleteUser(id: string): Promise<void> {
  await send(`${USERS_URL}/${encodeURIComponent(id)}`, { method: "DELETE" });
}

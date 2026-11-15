import type { Route } from "../lib/routes";

export type NotificationKind = "billing" | "workspace" | "domain" | "recipe" | "system";
/** How much it needs the reader: an error asks them to act, info only tells them. */
export type NotificationTone = "info" | "success" | "warning" | "error";

export interface Notification {
  readonly id: string;
  readonly kind: NotificationKind;
  readonly tone: NotificationTone;
  readonly title: string;
  /** One line for the menu and the list. */
  readonly summary: string;
  /** The full text, shown on the notification's own page. */
  readonly body: string;
  readonly createdAt: string;
  readonly read: boolean;
  /** Where the notification asks the reader to go next. */
  readonly action?: { readonly label: string; readonly route: Route };
}

// The API has no notification routes yet; these samples let the menu and pages be built first.
// Kept in memory, so marking one read lasts until the page reloads.
const INITIAL: readonly Notification[] = [
  { id: "n12", tone: "success", kind: "workspace", title: "Workspace is ready", summary: "demo-box finished starting and its agent is listening.", body: "Your workspace demo-box finished starting. The VM is up, the harness answered its health check, and the agent is ready for a new session.", createdAt: "2026-11-15T08:42:00Z", read: false, action: { label: "Open workspaces", route: { name: "home" } } },
  { id: "n11", tone: "warning", kind: "billing", title: "Low balance", summary: "Your balance is €12.40. Add funds to keep workspaces running.", body: "Your balance dropped to €12.40. Running workspaces and model usage draw from it, and a workspace stops when the balance runs out. Add funds to avoid an interruption.", createdAt: "2026-11-15T07:10:00Z", read: false, action: { label: "Go to billing", route: { name: "billing" } } },
  { id: "n10", tone: "warning", kind: "domain", title: "Domain renews soon", summary: "workspaced-demo.online expires on Dec 1, 2026.", body: "workspaced-demo.online is not set to renew automatically and expires on December 1, 2026. Once it expires, its DNS records stop answering and anyone may register it.", createdAt: "2026-11-14T16:00:00Z", read: false, action: { label: "Manage domains", route: { name: "domains" } } },
  { id: "n9", tone: "success", kind: "recipe", title: "New rating on your recipe", summary: "Someone rated “Deploy a Next.js app” 5 stars.", body: "Your public recipe “Deploy a Next.js app” received a new 5-star rating. It now averages 4.6 from 12 ratings.", createdAt: "2026-11-14T11:25:00Z", read: false, action: { label: "Open the store", route: { name: "store" } } },
  { id: "n8", tone: "error", kind: "workspace", title: "Scheduled task failed", summary: "“Nightly backup” in demo-box exited with an error.", body: "The scheduled task “Nightly backup” in demo-box ran at 02:00 and exited with an error. Open its session to read what the agent did and why it stopped.", createdAt: "2026-11-13T02:01:00Z", read: true },
  { id: "n7", tone: "success", kind: "billing", title: "Payment received", summary: "€25.00 was added to your balance.", body: "We received your payment of €25.00 and added it to your balance. Invoice INV-2026-0003 is available on the billing page.", createdAt: "2026-11-01T09:12:00Z", read: true, action: { label: "Go to billing", route: { name: "billing" } } },
  { id: "n6", tone: "info", kind: "system", title: "Scheduled maintenance", summary: "Workspaces may restart on Nov 3 between 02:00 and 03:00 UTC.", body: "We will update the platform on November 3 between 02:00 and 03:00 UTC. Running workspaces may restart once; files, sessions and secrets are kept.", createdAt: "2026-10-30T12:00:00Z", read: true },
  { id: "n5", tone: "success", kind: "domain", title: "Domain registered", summary: "pavel-labs.dev is yours for a year.", body: "pavel-labs.dev is registered to your account until March 12, 2027, and renews automatically. Add subdomains or records in settings.", createdAt: "2026-10-20T10:00:00Z", read: true, action: { label: "Manage domains", route: { name: "domains" } } },
  { id: "n4", tone: "error", kind: "billing", title: "Payment failed", summary: "A €10.00 top-up was declined by your bank.", body: "Your top-up of €10.00 was declined and nothing was charged. Try again with another card, or contact your bank.", createdAt: "2026-10-14T17:40:00Z", read: true, action: { label: "Go to billing", route: { name: "billing" } } },
  { id: "n3", tone: "info", kind: "workspace", title: "Workspace restarted", summary: "sandbox restarted to apply new secrets.", body: "sandbox restarted so its harness could read the secrets you changed. Sessions and files were kept.", createdAt: "2026-10-09T15:30:00Z", read: true },
  { id: "n2", tone: "success", kind: "recipe", title: "Recipe published", summary: "“Gmail drafts before sending” is now public.", body: "Your recipe “Gmail drafts before sending” is now public in the store, where anyone can apply and rate it.", createdAt: "2026-10-05T09:00:00Z", read: true, action: { label: "Open the store", route: { name: "store" } } },
  { id: "n1", tone: "info", kind: "system", title: "Welcome to workspaced", summary: "Create a workspace to get your first agent.", body: "Welcome! A workspace is a sandbox with its own agent. Create one from the sidebar, then start a session to give the agent a task.", createdAt: "2026-10-01T08:00:00Z", read: true },
];

let SAMPLE = [...INITIAL];

/** Restores the sample to its first state; for tests. */
export function resetSample() {
  SAMPLE = [...INITIAL];
}

export async function listNotifications(): Promise<Notification[]> {
  return [...SAMPLE];
}

export async function setRead(ids: readonly string[], read: boolean): Promise<Notification[]> {
  SAMPLE = SAMPLE.map((n) => (ids.includes(n.id) ? { ...n, read } : n));
  return listNotifications();
}

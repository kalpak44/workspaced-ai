import { send } from "./http";

/** What the account has to spend, what its workspaces spend, and every top-up that paid into it. */
export interface Billing {
  /** In cents, rounded down. */
  readonly balanceCents: number;
  readonly currency: string;
  /** Charged on top of a top-up, in percent; the balance gets the amount before it. */
  readonly vatPercent: number;
  /** Taken from the balance to start a workspace paused for want of funds again, in cents. */
  readonly reactivationFeeCents: number;
  /** How long a workspace stays paused for want of funds before it is deleted, in hours. */
  readonly pausedForHours: number;
  /** What the running workspaces cost together over a month, in cents; paused ones cost nothing. */
  readonly monthlySpendCents: number;
  /** When the balance stops paying for the running workspaces and they are paused; null while none is charged. */
  readonly fundedUntil: string | null;
  /** Every charged workspace, paused or not, oldest first. */
  readonly workspaces: readonly WorkspaceSpend[];
  /** What was charged each of the last 30 days anything was, newest first. */
  readonly usage: readonly DailySpend[];
  /** Newest first; checkouts abandoned at Stripe are left out. */
  readonly topUps: readonly TopUp[];
}

export interface WorkspaceSpend {
  readonly id: string;
  readonly name: string;
  readonly monthlyCents: number;
  /** While it is paused for want of funds, when it is deleted unless funds start it again; null while it runs. */
  readonly deletesAt: string | null;
}

export interface DailySpend {
  /** The day in UTC, as 2026-11-16. */
  readonly date: string;
  readonly amountCents: number;
}

export interface TopUp {
  readonly id: string;
  readonly amountCents: number;
  readonly status: "PENDING" | "PAID" | "FAILED" | "EXPIRED";
  readonly createdAt: string;
  readonly paidAt: string | null;
  /** The number Stripe gave the invoice; null until it is issued. */
  readonly invoiceNumber: string | null;
  readonly invoiceAvailable: boolean;
}

export interface Checkout {
  readonly id: string;
  /** Stripe's payment page, which sends the browser back to /billing?top_up=<id>&outcome=… */
  readonly checkoutUrl: string;
}

export const BILLING_URL = "/api/v1/billing";

export async function fetchBilling(): Promise<Billing> {
  return (await send(BILLING_URL)).json();
}

export async function startTopUp(amountCents: number): Promise<Checkout> {
  return (await send(`${BILLING_URL}/top-ups`, { method: "POST", body: JSON.stringify({ amountCents }) })).json();
}

/** Has the API read the payment back from Stripe, so a return from checkout sees it credited. */
export async function refreshTopUp(id: string): Promise<TopUp> {
  return (await send(`${BILLING_URL}/top-ups/${encodeURIComponent(id)}/refresh`, { method: "POST" })).json();
}

/** Redirects to Stripe's PDF, so a plain link downloads it with the sign-in cookie. */
export function invoiceUrl(id: string): string {
  return `${BILLING_URL}/top-ups/${encodeURIComponent(id)}/invoice`;
}

/** Leaves the app for Stripe; an object so tests can stub it, since jsdom cannot navigate. */
export const browser = {
  open(url: string): void {
    globalThis.location.assign(url);
  },
  openTab(url: string): void {
    globalThis.open(url, "_blank", "noopener");
  },
};

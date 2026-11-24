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
  /** How long the balance must pay for every workspace, a new one included, before it can be created, in hours. */
  readonly requiredHours: number;
  /** What the running workspaces cost together over a month, in cents; paused ones cost nothing. */
  readonly monthlySpendCents: number;
  /** When the balance stops paying for the running workspaces and they are paused; null while none is charged. */
  readonly fundedUntil: string | null;
  /** Every charged workspace, paused or not, oldest first. */
  readonly workspaces: readonly WorkspaceSpend[];
  /** What was charged each of the last 30 days anything was, newest first. */
  readonly usage: readonly DailySpend[];
  /** The card automatic top-ups charge, e.g. "Visa •••• 4242"; null while none is saved. */
  readonly card: string | null;
  readonly autoTopUp: AutoTopUp;
  /** Newest first; checkouts abandoned at Stripe are left out. */
  readonly topUps: readonly TopUp[];
}

/** While enabled, the saved card is charged amountCents, plus VAT, each time the balance drops below belowCents. */
export interface AutoTopUp {
  readonly enabled: boolean;
  readonly belowCents: number;
  readonly amountCents: number;
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
  /** Charged to the saved card by an automatic top-up. */
  readonly automatic: boolean;
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

/** Stripe's page that saves a card without charging it; it comes back with ?card_session=<id>. */
export async function startCardSetup(): Promise<string> {
  const body: { checkoutUrl: string } = await (await send(`${BILLING_URL}/card`, { method: "POST" })).json();
  return body.checkoutUrl;
}

/** Has the API keep the card the setup session saved, so it shows before Stripe's webhook arrives. */
export async function confirmCard(sessionId: string): Promise<void> {
  await send(`${BILLING_URL}/card/confirm`, { method: "POST", body: JSON.stringify({ sessionId }) });
}

/** Forgets the card, which turns automatic top-ups off. */
export async function removeCard(): Promise<void> {
  await send(`${BILLING_URL}/card`, { method: "DELETE" });
}

export async function saveAutoTopUp(settings: AutoTopUp): Promise<AutoTopUp> {
  return (await send(`${BILLING_URL}/auto-top-up`, { method: "PUT", body: JSON.stringify(settings) })).json();
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

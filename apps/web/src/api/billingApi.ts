/** What the account has to spend, and every charge or top-up that changed it. */
export interface Billing {
  /** In cents; workspaces and model usage draw it down. */
  readonly balanceCents: number;
  readonly invoices: readonly Invoice[];
}

export interface Invoice {
  readonly id: string;
  /** What the customer quotes to support, e.g. "INV-2026-0042". */
  readonly number: string;
  readonly issuedAt: string;
  readonly description: string;
  readonly amountCents: number;
  readonly status: "PAID" | "PENDING" | "FAILED";
}

// The API has no billing routes yet; this sample lets the page be built and reviewed first.
// Swap it for a send() to the real route once one exists, and the hook and page stay as they are.
const SAMPLE: Billing = {
  balanceCents: 1240,
  invoices: [
    { id: "inv3", number: "INV-2026-0003", issuedAt: "2026-11-01T09:12:00Z", description: "Balance top-up", amountCents: 2500, status: "PAID" },
    { id: "inv2", number: "INV-2026-0002", issuedAt: "2026-10-14T17:40:00Z", description: "Balance top-up", amountCents: 1000, status: "FAILED" },
    { id: "inv1", number: "INV-2026-0001", issuedAt: "2026-10-02T08:05:00Z", description: "Balance top-up", amountCents: 1000, status: "PAID" },
  ],
};

export async function fetchBilling(): Promise<Billing> {
  return SAMPLE;
}

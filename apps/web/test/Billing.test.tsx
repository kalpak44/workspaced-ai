import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { browser } from "../src/api/billingApi";
import { UserMenu } from "../src/components/organisms/UserMenu";
import { formatMoney } from "../src/lib/format";
import { installFakeApi } from "./fakeApi";

async function renderBilling(path = "/billing") {
  const api = installFakeApi();
  globalThis.history.replaceState(null, "", path);
  render(<App />);
  await screen.findByText("Current balance");
  return api;
}

/** The billing page, on a fake API the test has already set up. */
async function openBilling() {
  globalThis.history.replaceState(null, "", "/billing");
  render(<App />);
  await screen.findByText("Current balance");
}

const balance = () => within(screen.getByRole("region", { name: "Balance" }));

describe("billing", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows the balance in the account menu and opens the billing page from it", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<UserMenu theme="light" onThemeChange={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const item = await screen.findByRole("menuitem", { name: /^Billing/ });
    expect(await within(item).findByText("€12.40")).toBeInTheDocument();
    await user.click(item);
    expect(location.pathname).toBe("/billing");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows the current balance and every top-up with its invoice", async () => {
    await renderBilling();
    expect(balance().getByText("€12.40")).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getAllByText("WSAI-0001")).toHaveLength(2);
    expect(screen.getByRole("button", { name: /Download invoice for €25\.00/ })).toBeEnabled();
    expect(screen.getByRole("button", { name: /Download invoice for €10\.00/ })).toBeDisabled();
  });

  it("shows what each workspace costs, what was charged, and until when the balance lasts", async () => {
    const api = installFakeApi();
    api.billing.monthlySpendCents = 1780;
    api.billing.fundedUntil = new Date(Date.now() + (25 * 24 + 2) * 60 * 60 * 1000).toISOString();
    api.billing.workspaces = [
      { id: "w1", name: "research-box", monthlyCents: 890, deletesAt: null },
      { id: "w2", name: "scraper", monthlyCents: 890, deletesAt: null },
    ];
    api.billing.usage = [
      { date: "2026-11-16", amountCents: 59 },
      { date: "2026-11-15", amountCents: 30 },
    ];
    globalThis.history.replaceState(null, "", "/billing");
    render(<App />);
    await screen.findByText("Current balance");

    expect(balance().getByText(/about 25 days at €0\.59 a day/)).toBeInTheDocument();
    const workspaces = within(screen.getByRole("table", { name: "Workspaces" }));
    expect(workspaces.getByRole("row", { name: "research-box €0.29 €8.90" })).toBeInTheDocument();
    expect(workspaces.getByRole("row", { name: "Total €0.59 €17.80" })).toBeInTheDocument();
    const days = within(screen.getByRole("table", { name: "Daily charges" }));
    expect(days.getByRole("row", { name: "Nov 16, 2026 €0.59" })).toBeInTheDocument();
    expect(days.getAllByRole("row")).toHaveLength(3);
  });

  it("shows a paused workspace with when it is deleted and the fee to start it again", async () => {
    const api = installFakeApi();
    api.billing.balanceCents = 3;
    api.billing.workspaces = [{ id: "w1", name: "research-box", monthlyCents: 890, deletesAt: "2026-11-20T14:00:00Z" }];
    globalThis.history.replaceState(null, "", "/billing");
    render(<App />);
    await screen.findByText("Current balance");

    expect(balance().getByText("Your workspaces are paused until you add funds.")).toBeInTheDocument();
    const row = within(screen.getByRole("table", { name: "Workspaces" })).getByRole("row", { name: /research-box/ });
    expect(row).toHaveTextContent(/Paused · deleted Nov 20, \d+:00 [AP]M unless you add funds/);
    expect(screen.getByText(/add funds within 3 days and they start again, for a €5\.00 reactivation fee each/)).toBeInTheDocument();
    expect(screen.getByRole("alert", { name: "Funds warning" })).toHaveTextContent(
      "A workspace is paused because the balance ran out. Add funds to start it again: each costs a €5.00 reactivation fee on top of its hours.",
    );
  });

  it("warns a week before the balance runs out of the fee and the deletion that follow", async () => {
    const api = installFakeApi();
    api.billing.monthlySpendCents = 890;
    api.billing.fundedUntil = new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString();
    api.billing.workspaces = [{ id: "w1", name: "research-box", monthlyCents: 890, deletesAt: null }];
    const user = userEvent.setup();
    await openBilling();

    const warning = screen.getByRole("alert", { name: "Funds warning" });
    expect(warning).toHaveTextContent(/^Your balance runs out on .+\. Your workspaces are then paused, and starting each again costs a €5\.00 reactivation fee\. Any still paused 3 days later is deleted/);
    await user.click(within(warning).getByRole("button", { name: "Add funds" }));
    expect(screen.getByRole("dialog", { name: "Add funds" })).toBeInTheDocument();
  });

  it("does not warn while the balance lasts more than a week", async () => {
    const api = installFakeApi();
    api.billing.fundedUntil = new Date(Date.now() + 8 * 24 * 60 * 60 * 1000).toISOString();
    await openBilling();
    expect(screen.queryByRole("alert", { name: "Funds warning" })).not.toBeInTheDocument();
  });

  it("says when nothing draws on the balance", async () => {
    await renderBilling();
    expect(balance().getByText("No workspace is drawing on it.")).toBeInTheDocument();
    expect(screen.getByText("Nothing is charged yet. Workspaces you create are charged here.")).toBeInTheDocument();
  });

  it("sends the browser to Stripe to save a card and keeps it on the way back", async () => {
    const open = vi.spyOn(browser, "open").mockImplementation(() => {});
    const user = userEvent.setup();
    const api = await renderBilling();
    const section = within(screen.getByRole("region", { name: "Automatic top-up" }));
    expect(section.getByText("No card saved")).toBeInTheDocument();
    expect(section.getByRole("checkbox", { name: "Top up automatically" })).toBeDisabled();

    await user.click(section.getByRole("button", { name: "Save a card" }));
    expect(open).toHaveBeenCalledWith("https://checkout.stripe.test/setup");

    cleanup();
    api.billing.cardSessions.set("cs_1", "Visa •••• 4242");
    globalThis.history.replaceState(null, "", "/billing?card_session=cs_1");
    render(<App />);
    expect(await screen.findByText("Your card was saved. Turn on automatic top-ups to use it.")).toBeInTheDocument();
    expect(await screen.findByText("Visa •••• 4242")).toBeInTheDocument();
    expect(location.search).toBe("");
  });

  it("turns automatic top-ups on with the amounts chosen, and off with the card", async () => {
    const api = installFakeApi();
    api.billing.card = "Visa •••• 4242";
    const user = userEvent.setup();
    await openBilling();
    const section = within(screen.getByRole("region", { name: "Automatic top-up" }));
    const save = section.getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();

    await user.click(section.getByRole("checkbox", { name: "Top up automatically" }));
    await user.clear(section.getByLabelText("When the balance drops below (EUR)"));
    await user.type(section.getByLabelText("When the balance drops below (EUR)"), "15");
    await user.clear(section.getByLabelText("Add (EUR)"));
    await user.type(section.getByLabelText("Add (EUR)"), "30");
    expect(section.getByText("Charged as €36.00 with VAT.")).toBeInTheDocument();
    await user.click(save);

    await waitFor(() => expect(api.billing.savedAutoTopUps).toEqual([{ enabled: true, belowCents: 1500, amountCents: 3000 }]));
    await waitFor(() => expect(section.getByRole("checkbox", { name: "Top up automatically" })).toBeChecked());

    await user.click(section.getByRole("button", { name: "Remove card" }));
    expect(await section.findByText("No card saved")).toBeInTheDocument();
    expect(section.getByRole("checkbox", { name: "Top up automatically" })).not.toBeChecked();
  });

  it("refuses an amount outside what a top-up can be", async () => {
    const api = installFakeApi();
    api.billing.card = "Visa •••• 4242";
    const user = userEvent.setup();
    await openBilling();
    const section = within(screen.getByRole("region", { name: "Automatic top-up" }));

    await user.clear(section.getByLabelText("Add (EUR)"));
    await user.type(section.getByLabelText("Add (EUR)"), "2");

    expect(section.getByText("Between €5.00 and €1,000.00.")).toBeInTheDocument();
    expect(section.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("marks a top-up the card was charged for automatically", async () => {
    const api = installFakeApi();
    api.billing.topUps = [{ id: "t3", amountCents: 2000, status: "PAID", createdAt: "2026-11-16T10:00:00Z", paidAt: "2026-11-16T10:00:00Z", invoiceNumber: "WSAI-0003", invoiceAvailable: true, automatic: true }];
    await openBilling();
    expect(within(screen.getByRole("region", { name: "Top-ups" })).getByText("Auto")).toBeInTheDocument();
  });

  it("opens the invoice in a new tab", async () => {
    const openTab = vi.spyOn(browser, "openTab").mockImplementation(() => {});
    const user = userEvent.setup();
    await renderBilling();
    await user.click(screen.getByRole("button", { name: /Download invoice for €25\.00/ }));
    expect(openTab).toHaveBeenCalledWith("/api/v1/billing/top-ups/t1/invoice");
  });

  it("sends the browser to Stripe for the amount chosen", async () => {
    const open = vi.spyOn(browser, "open").mockImplementation(() => {});
    const user = userEvent.setup();
    const api = await renderBilling();
    await user.click(screen.getByRole("button", { name: "Add funds" }));
    await user.click(screen.getByRole("radio", { name: "€50.00" }));
    expect(screen.getByLabelText("Or another amount (EUR)")).toHaveValue(50);
    const summary = within(screen.getByLabelText("Summary"));
    expect(summary.getByText("€10.00")).toBeInTheDocument();
    expect(summary.getByText("€60.00")).toBeInTheDocument();
    expect(summary.getByText("€62.40")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Or another amount (EUR)"));
    expect(screen.getByRole("button", { name: "Pay" })).toBeDisabled();
    await user.type(screen.getByLabelText("Or another amount (EUR)"), "2");
    expect(screen.getByText("Enter between €5.00 and €1,000.00.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay" })).toBeDisabled();
    await user.type(screen.getByLabelText("Or another amount (EUR)"), "0");
    await user.type(screen.getByLabelText("Or another amount (EUR)"), "{Backspace}{Backspace}100");
    expect(screen.getByRole("radio", { name: "€100.00" })).toHaveAttribute("aria-checked", "true");
    await user.clear(screen.getByLabelText("Or another amount (EUR)"));
    await user.type(screen.getByLabelText("Or another amount (EUR)"), "20");
    await user.click(screen.getByRole("button", { name: "Pay €24.00" }));
    await vi.waitFor(() => expect(open).toHaveBeenCalledWith("https://checkout.stripe.test/t3"));
    expect(api.billing.started).toEqual([2000]);
  });

  it("offers a first top-up sized to one workspace, and says how long it lasts", async () => {
    const api = installFakeApi();
    api.billing.topUps = [];
    await openBilling();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Add funds" }));

    const amounts = within(screen.getByRole("radiogroup", { name: "Amount" })).getAllByRole("radio");
    expect(amounts.map((amount) => amount.textContent)).toEqual(["€10.00", "€25.00", "€50.00"]);
    expect(screen.getByRole("radio", { name: "€10.00" })).toHaveAttribute("aria-checked", "true");
    expect(await screen.findByRole("status", { name: "What it buys" })).toHaveTextContent(
      "€10.00 lasts about 4 weeks of a Starter workspace at €0.35 a day.",
    );
  });

  it("takes no payment when the API does not say what VAT to charge", async () => {
    const api = installFakeApi();
    api.billing.vatPercent = undefined;
    globalThis.history.replaceState(null, "", "/billing");
    render(<App />);
    await screen.findByText("Current balance");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Add funds" }));
    expect(await screen.findByText(/VAT cannot be worked out/)).toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay" })).toBeDisabled();
  });

  it("says so when payments are unavailable", async () => {
    const open = vi.spyOn(browser, "open").mockImplementation(() => {});
    const user = userEvent.setup();
    const api = await renderBilling();
    api.billing.failStart = 503;
    await user.click(screen.getByRole("button", { name: "Add funds" }));
    await user.click(screen.getByRole("button", { name: "Pay €30.00" }));
    expect(await screen.findByText("Payments are unavailable right now. Please try again later.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(open).not.toHaveBeenCalled();
  });

  it("credits a payment on the way back from Stripe and clears the return address", async () => {
    installFakeApi().billing.topUps.unshift({ id: "t9", amountCents: 5000, status: "PENDING", createdAt: "2026-11-15T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false, automatic: false });
    globalThis.history.replaceState(null, "", "/billing?top_up=t9&outcome=success");
    render(<App />);
    expect(await screen.findByText("€50.00 was added to your balance.")).toBeInTheDocument();
    expect(await balance().findByText("€62.40")).toBeInTheDocument();
    expect(location.search).toBe("");
  });

  it("says a payment is still clearing when Stripe has not confirmed it", async () => {
    const api = installFakeApi();
    api.billing.nextStatus = "PENDING";
    api.billing.topUps.unshift({ id: "t9", amountCents: 5000, status: "PENDING", createdAt: "2026-11-15T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false, automatic: false });
    globalThis.history.replaceState(null, "", "/billing?top_up=t9&outcome=success");
    render(<App />);
    expect(await screen.findByText(/Your payment is being confirmed/)).toBeInTheDocument();
  });

  it("reports a cancelled checkout without asking the API", async () => {
    const api = await renderBilling("/billing?top_up=t1&outcome=cancelled");
    expect(await screen.findByText("Payment cancelled. Nothing was charged.")).toBeInTheDocument();
    expect(api.billing.refreshed).toEqual([]);
  });

  it("formats cents as dollars", () => {
    expect(formatMoney(1240)).toBe("€12.40");
    expect(formatMoney(100000)).toBe("€1,000.00");
  });
});

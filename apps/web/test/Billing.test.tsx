import { render, screen, within } from "@testing-library/react";
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
      { id: "w1", name: "research-box", monthlyCents: 890 },
      { id: "w2", name: "scraper", monthlyCents: 890 },
    ];
    api.billing.usage = [
      { date: "2026-11-16", amountCents: 59 },
      { date: "2026-11-15", amountCents: 30 },
    ];
    globalThis.history.replaceState(null, "", "/billing");
    render(<App />);
    await screen.findByText("Current balance");

    expect(balance().getByText(/about 25 days at €17\.80 a month/)).toBeInTheDocument();
    const workspaces = within(screen.getByRole("table", { name: "Workspaces" }));
    expect(workspaces.getByRole("row", { name: "research-box €8.90" })).toBeInTheDocument();
    expect(workspaces.getByRole("row", { name: "Total €17.80" })).toBeInTheDocument();
    const days = within(screen.getByRole("table", { name: "Daily charges" }));
    expect(days.getByRole("row", { name: "Nov 16, 2026 €0.59" })).toBeInTheDocument();
    expect(days.getAllByRole("row")).toHaveLength(3);
  });

  it("says when nothing draws on the balance", async () => {
    await renderBilling();
    expect(balance().getByText("No workspace is drawing on it.")).toBeInTheDocument();
    expect(screen.getByText("Nothing is charged yet. Workspaces you create are charged here.")).toBeInTheDocument();
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
    installFakeApi().billing.topUps.unshift({ id: "t9", amountCents: 5000, status: "PENDING", createdAt: "2026-11-15T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false });
    globalThis.history.replaceState(null, "", "/billing?top_up=t9&outcome=success");
    render(<App />);
    expect(await screen.findByText("€50.00 was added to your balance.")).toBeInTheDocument();
    expect(await balance().findByText("€62.40")).toBeInTheDocument();
    expect(location.search).toBe("");
  });

  it("says a payment is still clearing when Stripe has not confirmed it", async () => {
    const api = installFakeApi();
    api.billing.nextStatus = "PENDING";
    api.billing.topUps.unshift({ id: "t9", amountCents: 5000, status: "PENDING", createdAt: "2026-11-15T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false });
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

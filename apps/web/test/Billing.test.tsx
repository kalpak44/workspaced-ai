import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { UserMenu } from "../src/components/organisms/UserMenu";
import { formatMoney } from "../src/lib/format";
import { installFakeApi } from "./fakeApi";

async function renderBilling() {
  installFakeApi();
  globalThis.history.replaceState(null, "", "/billing");
  render(<App />);
  await screen.findByText("Current balance");
}

describe("billing", () => {
  it("shows the balance in the account menu and opens the billing page from it", async () => {
    const user = userEvent.setup();
    render(<UserMenu theme="light" onThemeChange={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const item = await screen.findByRole("menuitem", { name: /^Billing/ });
    expect(await within(item).findByText("$12.40")).toBeInTheDocument();
    await user.click(item);
    expect(location.pathname).toBe("/billing");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows the current balance and every invoice", async () => {
    await renderBilling();
    expect(within(screen.getByRole("region", { name: "Balance" })).getByText("$12.40")).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Download INV-2026-0003" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Download INV-2026-0002" })).toBeDisabled();
  });

  it("previews the balance after a top-up and charges nothing yet", async () => {
    const user = userEvent.setup();
    await renderBilling();
    await user.click(screen.getByRole("button", { name: "Add funds" }));
    await user.click(screen.getByRole("radio", { name: "$50.00" }));
    expect(screen.getByText("$62.40")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Or another amount (USD)"), "2");
    expect(screen.getByText("Enter between $5.00 and $1,000.00.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay" })).toBeDisabled();
    await user.type(screen.getByLabelText("Or another amount (USD)"), "0");
    await user.click(screen.getByRole("button", { name: "Pay $20.00" }));
    expect(await screen.findByText(/\$20\.00 was not charged/)).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Balance" })).getByText("$12.40")).toBeInTheDocument();
  });

  it("formats cents as dollars", () => {
    expect(formatMoney(1240)).toBe("$12.40");
    expect(formatMoney(100000)).toBe("$1,000.00");
  });
});

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { browser } from "../src/api/billingApi";
import type { Workspace } from "../src/api/workspacesApi";
import { keepAfterTopUp, takeKeepAfterTopUp } from "../src/hooks/useKeepWorkspace";
import { installFakeApi } from "./fakeApi";

const demo: Workspace = { id: "trial", name: "My blog", modelProvider: "openrouter", model: null, timeZone: "UTC", status: "READY", restartRequired: false, demo: true, serverType: "cx23", expiresAt: new Date(Date.now() + 3_600_000).toISOString() };

function visit(path: string) {
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}

describe("keeping the demo", () => {
  it("keeps it from the banner when the balance covers its first week", async () => {
    installFakeApi([demo]);
    const user = userEvent.setup();
    visit("/workspaces/trial");
    const banner = await screen.findByRole("status", { name: "Demo workspace" });
    await user.click(within(banner).getByRole("button", { name: "Keep it" }));

    const dialog = await screen.findByRole("dialog", { name: "Keep My blog" });
    expect(dialog).toHaveTextContent("stays exactly as it is");
    await user.click(within(dialog).getByRole("button", { name: "Keep it" }));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Keep My blog" })).not.toBeInTheDocument());
    expect(screen.queryByRole("status", { name: "Demo workspace" })).not.toBeInTheDocument();
  });

  it("offers to add what is missing and keeps it once the payment is back", async () => {
    const api = installFakeApi([demo]);
    api.billing.balanceCents = 100;
    const opened = vi.spyOn(browser, "open").mockImplementation(() => {});
    const user = userEvent.setup();
    visit("/workspaces/trial?keep");

    const dialog = await screen.findByRole("dialog", { name: "Keep My blog" });
    expect(globalThis.location.search).toBe("");
    await user.click(within(dialog).getByRole("button", { name: "Keep it" }));
    await waitFor(() => expect(dialog).toHaveTextContent(/needs .*1\.46 more/));
    // The API takes no top-up under €5, so that is what is offered, with its VAT shown.
    await user.click(within(dialog).getByRole("button", { name: "Add €5.00 and keep it" }));
    await waitFor(() => expect(opened).toHaveBeenCalledWith(expect.stringContaining("https://checkout.stripe.test/")));
    expect(api.billing.started).toEqual([500]);
    expect(takeKeepAfterTopUp()).toBe("trial");
  });

  it("finishes keeping it when the browser returns from a paid top-up", async () => {
    const api = installFakeApi([demo]);
    api.billing.balanceCents = 0;
    api.billing.topUps.unshift({ id: "t9", amountCents: 500, status: "PENDING", createdAt: "2026-11-24T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false, automatic: false });
    keepAfterTopUp("trial");
    visit("/billing?top_up=t9&outcome=success");

    await waitFor(() => expect(globalThis.location.pathname).toBe("/workspaces/trial"));
    await waitFor(() => expect(screen.queryByRole("status", { name: "Demo workspace" })).not.toBeInTheDocument());
    expect(takeKeepAfterTopUp()).toBeNull();
  });

  it("forgets a keep whose payment was cancelled", async () => {
    installFakeApi([demo]);
    keepAfterTopUp("trial");
    visit("/billing?top_up=t9&outcome=cancelled");
    expect(await screen.findByText("Payment cancelled. Nothing was charged.")).toBeInTheDocument();
    expect(takeKeepAfterTopUp()).toBe("trial");
  });
});

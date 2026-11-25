import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { browser, signUp } from "../src/api/accountApi";
import type { Referrals } from "../src/api/referralsApi";
import { installFakeApi } from "./fakeApi";

const TERMS = {
  referee: { percent: 10, capCents: 1000, days: 90 },
  referrer: { percent: 10, capCents: 5000, days: 365 },
};

const REFERRALS: Referrals = {
  enabled: true,
  eligible: true,
  code: "k7m2xq9p",
  link: "https://workspaced.online/?ref=k7m2xq9p",
  ...TERMS,
  invited: 3,
  paying: 2,
  earnedCents: 1250,
  friends: [
    { signedUpAt: "2026-11-20T10:00:00Z", firstPaidAt: null, status: "SIGNED_UP", earnedCents: 0, earningUntil: "2027-11-20T10:00:00Z" },
    { signedUpAt: "2026-10-01T10:00:00Z", firstPaidAt: "2026-10-02T10:00:00Z", status: "EARNING", earnedCents: 750, earningUntil: "2027-10-01T10:00:00Z" },
    { signedUpAt: "2026-09-01T10:00:00Z", firstPaidAt: "2026-09-01T12:00:00Z", status: "FINISHED", earnedCents: 500, earningUntil: "2027-09-01T10:00:00Z" },
  ],
};

function open(referrals: Referrals) {
  const api = installFakeApi();
  api.auth.account = { ...api.auth.account, role: "USER" };
  const fallback = api.fetchFn.getMockImplementation()!;
  api.fetchFn.mockImplementation(async (input, init) =>
    String(input).endsWith("/api/v1/account/referrals") ? new Response(JSON.stringify(referrals)) : fallback(input, init));
  globalThis.history.replaceState(null, "", "/settings/referrals");
  render(<App />);
}

describe("referrals", () => {
  it("shows the link, what both sides get, the funnel and each friend without a name", async () => {
    open(REFERRALS);

    expect(await screen.findByRole("textbox", { name: "Your invitation link" })).toHaveValue("https://workspaced.online/?ref=k7m2xq9p");
    expect(screen.getByRole("heading", { name: "Give friends 10% extra, earn 10% of what they pay" })).toBeInTheDocument();
    const tiles = screen.getByRole("list", { name: "Your referrals" });
    expect(tiles).toHaveTextContent("Friends invited3");
    expect(tiles).toHaveTextContent("Paying friends2");
    expect(tiles).toHaveTextContent("Earned so far€12.50");
    const funnel = within(screen.getByRole("list", { name: "Your invitation funnel" })).getAllByRole("listitem");
    expect(funnel[1]).toHaveTextContent("Paid2 67%");
    expect(funnel[2]).toHaveTextContent("Still earning you credit1 33%");
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(/Friend 3.*Signed up/);
    expect(rows[1]).toHaveTextContent(/Friend 2.*Paying.*€7\.50of €50\.00/);
    expect(rows[2]).toHaveTextContent(/Friend 1.*Finished/);
  });

  it("copies the link", async () => {
    open(REFERRALS);
    // After setup, which puts a clipboard of its own in place.
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    await user.click(await screen.findByRole("button", { name: "Copy link" }));
    expect(writeText).toHaveBeenCalledWith("https://workspaced.online/?ref=k7m2xq9p");
    expect(await screen.findByText("Link copied.")).toBeInTheDocument();
  });

  it("tells an admin they take no part", async () => {
    open({ ...REFERRALS, eligible: false, code: null, link: null, invited: 0, paying: 0, earnedCents: 0, friends: [] });

    expect(await screen.findByText(/Admins pay cost price/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Your invitation link" })).not.toBeInTheDocument();
  });

  it("passes the invitation code on to the sign-up", () => {
    const opened = vi.spyOn(browser, "open").mockImplementation(() => {});
    globalThis.history.replaceState(null, "", "/login?ref=k7m2xq9p");
    signUp();
    expect(opened).toHaveBeenCalledWith("/api/v1/oauth2/authorization/auth0?signup&ref=k7m2xq9p");
    globalThis.history.replaceState(null, "", "/");
  });
});

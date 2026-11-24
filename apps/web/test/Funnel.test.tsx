import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { browser, signUp } from "../src/api/accountApi";
import { installFakeApi } from "./fakeApi";

const FUNNEL = {
  from: "2026-10-26",
  to: "2026-11-24",
  total: { signedUp: 10, demoStarted: 6, paid: 2, retentionEligible: 4, retained: 1 },
  sources: [
    { source: "newsletter", campaign: "nov", counts: { signedUp: 7, demoStarted: 5, paid: 2, retentionEligible: 3, retained: 1 } },
    { source: null, campaign: null, counts: { signedUp: 3, demoStarted: 1, paid: 0, retentionEligible: 1, retained: 0 } },
  ],
  tour: [
    { step: 0, reached: 6, open: 0, paused: 0, ended: 0 },
    { step: 5, reached: 4, open: 1, paused: 2, ended: 0 },
    { step: 18, reached: 1, open: 0, paused: 0, ended: 1 },
  ],
};

describe("funnel", () => {
  it("shows admins each step, the sources and where the tour lost people", async () => {
    const api = installFakeApi();
    const fallback = api.fetchFn.getMockImplementation()!;
    api.fetchFn.mockImplementation(async (input, init) =>
      String(input).includes("/api/v1/admin/funnel") ? new Response(JSON.stringify(FUNNEL)) : fallback(input, init));
    globalThis.history.replaceState(null, "", "/settings");
    render(<App />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Funnel" }));

    const tiles = await screen.findByRole("list", { name: "Funnel" });
    expect(tiles).toHaveTextContent("Paid220% of sign-ups");
    expect(tiles).toHaveTextContent("Still paying at 30 days125% of 4 old enough");
    expect(screen.getByRole("row", { name: /newsletter · nov/ })).toHaveTextContent("newsletter · nov752 29%1/3");
    expect(screen.getByRole("row", { name: /No source/ })).toBeInTheDocument();
    const steps = within(screen.getByRole("list", { name: "Tour steps" })).getAllByRole("listitem");
    expect(steps[1]).toHaveTextContent("6. Store42 skipped · 1 still here");
    expect(screen.getByText(/6 started it, 1 finished it \(17%\)/)).toBeInTheDocument();
  });

  it("passes the page's utm parameters on to the sign-up", () => {
    const opened = vi.spyOn(browser, "open").mockImplementation(() => {});
    globalThis.history.replaceState(null, "", "/login?utm_source=newsletter&utm_campaign=nov&other=x");
    signUp();
    expect(opened).toHaveBeenCalledWith("/api/v1/oauth2/authorization/auth0?signup&utm_source=newsletter&utm_campaign=nov");
    globalThis.history.replaceState(null, "", "/");
  });
});

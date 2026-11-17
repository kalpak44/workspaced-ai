import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { CreateWorkspaceDialog } from "../src/components/organisms/CreateWorkspaceDialog";
import { useWorkspaces } from "../src/hooks/useWorkspaces";
import { installFakeApi } from "./fakeApi";

/** The dialog creating through the real hook, so the API's answers reach it as they would in the app. */
function Dialog() {
  const { create } = useWorkspaces();
  return <CreateWorkspaceDialog initialName="Research" onCreate={(workspace) => create(workspace)} onClose={() => {}} />;
}

type User = ReturnType<typeof userEvent.setup>;

async function openSizes(user: User) {
  render(<Dialog />);
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByRole("radio", { name: /cx23/ });
}

const warning = () => screen.queryByRole("status", { name: "Sizes unavailable" });

describe("VM sizes", () => {
  it("shows each size with its price, cheapest first, and starts on the default", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);

    const radios = screen.getAllByRole("radio");
    expect(radios.map((radio) => (radio as HTMLInputElement).value)).toEqual(["cx23", "cx33", "cpx22"]);
    expect(screen.getByRole("radio", { name: /cx23/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /cx23.*2 vCPU · 4 GB RAM · 40 GB disk.*€6\.59.*per month/ })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /cpx22/ })).toBeEnabled();
    expect(warning()).not.toBeInTheDocument();
  });

  it("warns while the low-cost sizes are sold out, picks nothing pricier for you, and checks again", async () => {
    const api = installFakeApi();
    for (const size of api.state.vmSizes.filter((s) => s.recommended)) size.available = false;
    const user = userEvent.setup();
    await openSizes(user);

    expect(warning()).toHaveTextContent("The low-cost sizes are unavailable right now.");
    expect(warning()).not.toHaveTextContent(/hetzner/i);
    expect(screen.getByRole("radio", { name: /cx23/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /cx23 · unavailable/ })).toBeInTheDocument();
    expect(screen.getAllByRole("radio").some((radio) => (radio as HTMLInputElement).checked)).toBe(false);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();

    await user.click(screen.getByRole("radio", { name: /cpx22/ }));
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();

    for (const size of api.state.vmSizes) size.available = true;
    await user.click(within(warning()!).getByRole("button", { name: "Check again" }));
    await waitFor(() => expect(warning()).not.toBeInTheDocument());
    expect(screen.getByRole("radio", { name: /cpx22/ })).toBeChecked();
  });

  it("leaves out a pricier size while it is unavailable", async () => {
    const api = installFakeApi();
    api.state.vmSizes[2].available = false;
    const user = userEvent.setup();
    await openSizes(user);

    expect(screen.getAllByRole("radio").map((radio) => (radio as HTMLInputElement).value)).toEqual(["cx23", "cx33"]);
  });

  it("tells a paying user how long the balance lasts with the size among their workspaces", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    api.billing.balanceCents = 5000;
    api.billing.monthlySpendCents = 890;
    const user = userEvent.setup();
    await openSizes(user);

    expect(await screen.findByRole("status", { name: "Balance" })).toHaveTextContent(
      "With it, your workspaces cost €15.49 a month. Your balance of €50.00 lasts about 3.2 months.",
    );
  });

  it("warns a paying user before the create when the balance is short of a month of everything", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    api.billing.monthlySpendCents = 890;
    const user = userEvent.setup();
    await openSizes(user);

    const hint = await screen.findByRole("status", { name: "Balance" });
    expect(hint).toHaveTextContent(
      "Your balance of €12.40 does not cover a month of your workspaces with this one (€15.49). Add €3.09, then create the workspace.",
    );
    expect(within(hint).getByRole("link", { name: "Add funds" })).toBeInTheDocument();
  });

  it("tells an admin nothing of the balance, since their workspaces are free", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);

    expect(screen.queryByRole("status", { name: "Balance" })).not.toBeInTheDocument();
  });

  it("says when the balance is short and links to adding funds", async () => {
    const api = installFakeApi();
    api.state.noFunds = true;
    api.billing.balanceCents = 500;
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.type(await screen.findByLabelText("OpenRouter API key"), "sk-or-v1-test");

    await user.click(screen.getByRole("button", { name: "Create workspace" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Your balance of €5.00 does not cover a month of cx23 (€6.59). Add €1.59, then create the workspace.");
    await user.click(within(alert).getByRole("link", { name: "Add funds" }));
    expect(globalThis.location.pathname).toBe("/billing");
    expect(api.rows.map((w) => w.name)).not.toContain("Research");
  });

  it("comes back to the sizes when the one chosen sells out during the create", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.type(await screen.findByLabelText("OpenRouter API key"), "sk-or-v1-test");
    api.state.vmSizes[0].available = false;

    await user.click(screen.getByRole("button", { name: "Create workspace" }));

    expect(await screen.findByRole("status", { name: "Sizes unavailable" })).toHaveTextContent("cx23 is no longer available.");
    await waitFor(() => expect(screen.getByRole("radio", { name: /cx23/ })).toBeDisabled());
    expect(api.rows.map((w) => w.name)).not.toContain("Research");
  });
});

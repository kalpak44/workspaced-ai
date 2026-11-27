import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { CreateWorkspaceDialog } from "../src/components/organisms/CreateWorkspaceDialog";
import { useWorkspaces } from "../src/hooks/useWorkspaces";
import { installFakeApi } from "./fakeApi";

/** The dialog creating through the real hook, so the API's answers reach it as they would in the app. */
function Dialog() {
  const { create } = useWorkspaces();
  return (
    <CreateWorkspaceDialog
      initialName="Research"
      onCreate={(workspace) => create(workspace)}
      onClose={() => {}}
    />
  );
}

type User = ReturnType<typeof userEvent.setup>;

async function openSizes(user: User) {
  render(<Dialog />);
  await user.click(screen.getByRole("button", { name: "Next" }));
  await screen.findByRole("radio", { name: /^Starter/ });
}

const warning = () => screen.queryByRole("status", { name: "Sizes unavailable" });

describe("VM sizes", () => {
  it("blocks creation with an exhausted trial connector and enables it for a funded connector", async () => {
    const api = installFakeApi();
    api.connectors.push(
      { id: "trial", name: "Trial", provider: "openrouter", trial: true },
      { id: "personal", name: "Personal", provider: "openrouter" },
    );
    api.state.trialRemaining = 0;
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This AI connector has no credits remaining.");
    expect(screen.getByRole("button", { name: "Create workspace" })).toBeDisabled();
    await user.selectOptions(screen.getByRole("combobox", { name: "AI connector" }), "personal");
    await waitFor(() => expect(screen.getByRole("button", { name: "Create workspace" })).toBeEnabled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "AI connector" }), "trial");
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "Create workspace" })).toBeDisabled();
  });

  it("shows each plan with its price, in order, and starts on the default", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);

    const radios = screen.getAllByRole("radio");
    expect(radios.map((radio) => (radio as HTMLInputElement).value)).toEqual(["cx23", "cx33", "cx43"]);
    expect(screen.getByRole("radio", { name: /^Starter/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Starter.*€0\.35 a day.*€10\.65 a month.*€0\.015\/hour.*2 vCPU.*4 GB RAM.*40 GB disk/ })).toBeInTheDocument();
    expect(screen.getByText("Prices exclude VAT, which is added when you top up. Billed by the hour from your balance; delete the workspace any time and its charges stop.")).toBeInTheDocument();
    expect(warning()).not.toBeInTheDocument();
  });

  it("badges the featured plan as recommended, and only that one", async () => {
    installFakeApi();
    await openSizes(userEvent.setup());

    expect(screen.getByRole("radio", { name: /^Builder Recommended/ })).not.toBeChecked();
    expect(screen.getAllByText("Recommended")).toHaveLength(1);
  });

  it("lets users choose Flex even while regular plans are available, and switch back", async () => {
    const api = installFakeApi();
    api.state.vmSizes[0].available = false;
    api.state.vmSizes.push({ plan: "Starter Flex", serverType: "cpx22", isDefault: false, cores: 2, memoryGb: 4, diskGb: 80, cpuType: "shared", architecture: "x86", location: "nbg1", monthlyNet: 34.979167, monthlyGross: 34.979167, recommended: false, featured: false, available: true });
    const user = userEvent.setup();
    await openSizes(user);

    expect(screen.getAllByRole("radio").map((radio) => (radio as HTMLInputElement).value)).toEqual(["cx23", "cx33", "cx43"]);
    expect(warning()).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show Flex tariffs" }));
    expect(screen.getByRole("radio", { name: /Starter Flex.*€1\.15 a day/ })).toBeChecked();
    expect(screen.getAllByRole("radio")).toHaveLength(1);
    expect(screen.getByText("Flex tariffs · higher price")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "Show normal tariffs" }));
    expect(screen.getByRole("radio", { name: /^Builder/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /^Starter/ })).toBeDisabled();
    expect(screen.queryByRole("radio", { name: /Flex/ })).not.toBeInTheDocument();
  });

  it("keeps the tariff switch visible when Flex is sold out", async () => {
    const api = installFakeApi();
    api.state.vmSizes.push({ ...api.state.vmSizes[0], plan: "Starter Flex", serverType: "cpx22", recommended: false, isDefault: false, available: false });
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Show Flex tariffs" }));
    expect(screen.getByRole("radio", { name: /Starter Flex.*Sold out/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Show normal tariffs" }));
    expect(screen.getByRole("radio", { name: /^Starter/ })).toBeChecked();
  });

  it("shows a sold-out plan but does not let it be chosen", async () => {
    const api = installFakeApi();
    api.state.vmSizes[0].available = false;
    const user = userEvent.setup();
    await openSizes(user);

    expect(screen.getByRole("radio", { name: /Starter.*Sold out/ })).toBeDisabled();
    expect(screen.getAllByRole("radio").some((radio) => (radio as HTMLInputElement).checked)).toBe(false);
    expect(warning()).not.toBeInTheDocument();
  });

  it("offers the stand-in plan in place of the regular ones while they are all sold out, and says why", async () => {
    const api = installFakeApi();
    for (const size of api.state.vmSizes) size.available = false;
    api.state.vmSizes.push({ plan: "Starter Flex", serverType: "cpx22", isDefault: true, cores: 2, memoryGb: 4, diskGb: 80, cpuType: "shared", architecture: "x86", location: "nbg1", monthlyNet: 34.979167, monthlyGross: 34.979167, recommended: false, featured: false, available: true });
    api.state.vmSizes[0].isDefault = false;
    const user = userEvent.setup();
    await openSizes(user);

    expect(warning()).toHaveTextContent("Starter, Builder and Pro are sold out right now, usually for a few hours. Until they are back you can take one of these");
    expect(screen.getAllByRole("radio").map((radio) => (radio as HTMLInputElement).value)).toEqual(["cpx22"]);
    expect(screen.getByRole("radio", { name: /Starter Flex.*€1\.15 a day/ })).toBeChecked();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Show normal tariffs" }));
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    expect(screen.getAllByRole("radio").every((radio) => (radio as HTMLInputElement).disabled)).toBe(true);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Show Flex tariffs" }));
    expect(screen.getByRole("radio", { name: /Starter Flex/ })).toBeChecked();
  });

  it("emails the user once a plan is back, when they ask while every plan is sold out", async () => {
    const api = installFakeApi();
    for (const size of api.state.vmSizes) size.available = false;
    const user = userEvent.setup();
    await openSizes(user);

    await user.click(within(warning()!).getByRole("button", { name: "Email me when a plan is back" }));

    expect(await within(warning()!).findByText("We'll email you when a plan is back.")).toBeInTheDocument();
    expect(api.state.planWaiting).toBe(true);
  });

  it("remembers that the user is already waiting for a plan", async () => {
    const api = installFakeApi();
    for (const size of api.state.vmSizes) size.available = false;
    api.state.planWaiting = true;
    await openSizes(userEvent.setup());

    expect(await within(warning()!).findByText("We'll email you when a plan is back.")).toBeInTheDocument();
    expect(within(warning()!).queryByRole("button", { name: "Email me when a plan is back" })).not.toBeInTheDocument();
  });

  it("warns while every plan is sold out, and checks again", async () => {
    const api = installFakeApi();
    for (const size of api.state.vmSizes) size.available = false;
    const user = userEvent.setup();
    await openSizes(user);

    expect(warning()).toHaveTextContent("Starter, Builder and Pro are sold out right now.");
    expect(warning()).not.toHaveTextContent(/hetzner/i);
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();

    for (const size of api.state.vmSizes) size.available = true;
    await user.click(within(warning()!).getByRole("button", { name: "Check again" }));
    await waitFor(() => expect(warning()).not.toBeInTheDocument());
    await user.click(screen.getByRole("radio", { name: /Builder/ }));
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
  });

  it("tells a paying user how long the balance lasts with the size among their workspaces", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    api.billing.balanceCents = 5000;
    api.billing.monthlySpendCents = 890;
    const user = userEvent.setup();
    await openSizes(user);

    expect(await screen.findByRole("status", { name: "Balance" })).toHaveTextContent(
      "With it, your workspaces cost €0.64 a day (€19.55 a month). Your balance of €50.00 lasts about 2.6 months.",
    );
  });

  it("warns a paying user before the create when the balance is short of a week of everything", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    api.billing.balanceCents = 300;
    api.billing.monthlySpendCents = 890;
    const user = userEvent.setup();
    await openSizes(user);

    const hint = await screen.findByRole("status", { name: "Balance" });
    expect(hint).toHaveTextContent(
      "Your balance of €3.00 does not cover 7 days of your workspaces with this one (€4.50). Add €1.50, then create the workspace.",
    );
    expect(within(hint).getByRole("link", { name: "Add funds" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("radio", { name: /^Starter/ })).toBeInTheDocument();
  });

  it("updates Next when choosing a plan at or above the available balance", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    api.billing.balanceCents = 245;
    api.billing.monthlySpendCents = 0;
    const user = userEvent.setup();
    await openSizes(user);
    await screen.findByRole("status", { name: "Balance" });

    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
    await user.click(screen.getByRole("radio", { name: /^Pro/ }));
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("radio", { name: /^Starter/ }));
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
  });

  it("tells an admin nothing of the balance, since their workspaces are free", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);

    expect(screen.queryByRole("status", { name: "Balance" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
  });

  it("says when the balance is short and links to adding funds", async () => {
    const api = installFakeApi();
    api.state.noFunds = true;
    api.billing.balanceCents = 100;
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(await screen.findByRole("button", { name: "Paste a key instead" }));
    await user.type(screen.getByLabelText("OpenRouter API key"), "sk-or-v1-test");
    await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));

    await user.click(screen.getByRole("button", { name: "Create workspace" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Your balance of €1.00 does not cover 7 days of Starter (€2.45). Add €1.45, then create the workspace.");
    await user.click(within(alert).getByRole("link", { name: "Add funds" }));
    expect(globalThis.location.pathname).toBe("/billing");
    expect(api.rows.map((w) => w.name)).not.toContain("Research");
  });

  it("comes back to the sizes when the one chosen sells out during the create", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openSizes(user);
    await user.click(screen.getByRole("button", { name: "Next" }));
    await user.click(await screen.findByRole("button", { name: "Paste a key instead" }));
    await user.type(screen.getByLabelText("OpenRouter API key"), "sk-or-v1-test");
    await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
    api.state.vmSizes[0].available = false;

    await user.click(screen.getByRole("button", { name: "Create workspace" }));

    expect(await screen.findByRole("status", { name: "Sizes unavailable" })).toHaveTextContent("Starter is no longer available.");
    await waitFor(() => expect(screen.getByRole("radio", { name: /^Starter/ })).toBeDisabled());
    expect(api.rows.map((w) => w.name)).not.toContain("Research");
  });
});

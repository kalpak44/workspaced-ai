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

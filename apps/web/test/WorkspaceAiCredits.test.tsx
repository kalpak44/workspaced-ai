import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkspacePage } from "../src/components/pages/WorkspacePage";
import { SessionPage } from "../src/components/pages/SessionPage";
import { GeneralSettingsPage } from "../src/components/pages/GeneralSettingsPage";
import { installFakeApi } from "./fakeApi";

function setup(remaining = 0.5, trial = false, demo = false) {
  const api = installFakeApi();
  api.state.aiRemaining = remaining;
  api.state.trialRemaining = remaining;
  api.connectors.push({ id: "key", name: trial ? "Trial" : "Personal", provider: "openrouter", trial });
  api.workspaceConnectors.set("starter", "key");
  api.rows[0] = { ...api.rows[0], ownerId: api.auth.account.id, demo };
  return api;
}

const page = (api: ReturnType<typeof setup>) => <WorkspacePage workspace={api.rows[0]} deleting={false} onModelChanged={vi.fn()} />;
const refresh = () => act(() => { globalThis.dispatchEvent(new Event("focus")); });

describe("workspace AI credits", () => {
  it("warns owners below $1 and removes the warning after a top-up", async () => {
    const api = setup();
    render(page(api));
    expect(await screen.findByRole("status", { name: "AI credits" })).toHaveTextContent("$0.50 left");
    expect(screen.getByRole("link", { name: "Top up AI credits" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled();
    api.state.aiRemaining = 1;
    await userEvent.setup().click(screen.getByRole("button", { name: "Check again" }));
    await waitFor(() => expect(screen.queryByRole("status", { name: "AI credits" })).not.toBeInTheDocument());
  });

  it("tells collaborators to ask the owner and prevents new-session prompts at zero", async () => {
    const api = setup(0);
    api.rows[0] = { ...api.rows[0], ownerId: "another-owner" };
    render(page(api));
    expect(await screen.findByRole("alert", { name: "AI credits" })).toHaveTextContent("Ask the workspace owner to top up.");
    expect(screen.queryByRole("link", { name: "Top up AI credits" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    expect(api.prompts).toEqual([]);
  });

  it.each([false, true])("warns below $0.20 and shows a red error at zero for trial tokens (demo=%s)", async demo => {
    const api = setup(0.2, true, demo);
    api.auth.account = { ...api.auth.account, aiCreditWarningUsd: 0 };
    render(page(api));
    await screen.findByRole("textbox", { name: "Message" });
    api.state.trialRemaining = 0.19;
    refresh();
    const warning = await screen.findByRole("alert", { name: "AI credits" });
    expect(warning).toHaveTextContent("Trial AI credits are running low · $0.19 left.");
    expect(warning).toHaveClass("border-amber-200");
    expect(screen.queryByRole("link", { name: "Top up AI credits" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled();
    api.state.trialRemaining = 0;
    refresh();
    await waitFor(() => expect(screen.getByRole("alert", { name: "AI credits" })).toHaveTextContent("AI credits have run out."));
    expect(screen.getByRole("alert", { name: "AI credits" })).toHaveClass("border-rose-200");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    api.state.trialRemaining = 0.2;
    refresh();
    await waitFor(() => expect(screen.queryByRole("alert", { name: "AI credits" })).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled();
  });

  it("shows an exhausted-credit error in a demo even with a personal token", async () => {
    const api = setup(0, false, true);
    render(page(api));
    const banner = await screen.findByRole("alert", { name: "AI credits" });
    expect(banner).toHaveTextContent("AI credits have run out.");
    expect(banner).toHaveClass("border-rose-200");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("asks collaborators to contact the owner when trial credits are low", async () => {
    const api = setup(0.1, true);
    api.rows[0] = { ...api.rows[0], ownerId: "another-owner" };
    render(page(api));
    expect(await screen.findByRole("alert", { name: "AI credits" })).toHaveTextContent("Ask the workspace owner to add a funded AI connector.");
    expect(screen.queryByRole("link", { name: "AI connector settings" })).not.toBeInTheDocument();
  });

  it.each([false, true])("blocks an existing session and resumes after funding (trial=%s)", async trial => {
    const api = setup(0, trial);
    api.sessions.set("starter", [{ id: "old", title: "Existing", path: "/workspace/sessions/old", createdAt: new Date().toISOString(), available: true }]);
    render(<SessionPage workspace={api.rows[0]} sessionId="old" deleting={false} onTitle={vi.fn()} onModelChanged={vi.fn()} />);
    await screen.findByRole("alert", { name: "AI credits" });
    expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled();
    api.state.aiRemaining = 2;
    api.state.trialRemaining = 0.5;
    refresh();
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled());
    expect(screen.queryByRole("alert", { name: "AI credits" })).not.toBeInTheDocument();
  });

  it("persists a configurable threshold in account settings", async () => {
    const api = setup();
    render(<GeneralSettingsPage section="ai-connectors" />);
    const input = await screen.findByRole("spinbutton", { name: "Warning threshold (USD)" });
    const user = userEvent.setup();
    await user.clear(input);
    await user.type(input, "2.50");
    await user.click(screen.getByRole("button", { name: "Save threshold" }));
    await screen.findByText("AI credit warning threshold saved.");
    expect(api.auth.account.aiCreditWarningUsd).toBe(2.5);
  });
});

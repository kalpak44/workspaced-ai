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

  it.each([false, true])("hides trial credit banners and disables sending at zero (demo=%s)", async demo => {
    const api = setup(0, true, demo);
    render(page(api));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled());
    expect(screen.queryByRole("alert", { name: "AI credits" })).not.toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "AI credits" })).not.toBeInTheDocument();
    api.state.trialRemaining = 0.5;
    refresh();
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled());
    expect(screen.queryByRole("status", { name: "AI credits" })).not.toBeInTheDocument();
  });

  it("blocks an existing session and resumes after funding", async () => {
    const api = setup(0);
    api.sessions.set("starter", [{ id: "old", title: "Existing", path: "/workspace/sessions/old", createdAt: new Date().toISOString(), available: true }]);
    render(<SessionPage workspace={api.rows[0]} sessionId="old" deleting={false} onTitle={vi.fn()} onModelChanged={vi.fn()} />);
    await screen.findByRole("alert", { name: "AI credits" });
    expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled();
    api.state.aiRemaining = 2;
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

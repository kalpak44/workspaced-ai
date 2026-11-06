import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { SessionTtlPanel } from "../src/components/organisms/SessionTtlPanel";
import { installFakeApi } from "./fakeApi";

describe("workspace session TTL settings", () => {
  it("loads and saves retention through the API, separately for each workspace", async () => {
    const api = installFakeApi([
      { id: "first", name: "First", modelProvider: "openrouter", model: null, status: "READY", restartRequired: false },
      { id: "second", name: "Second", modelProvider: "openrouter", model: null, status: "READY", restartRequired: false },
    ]);
    const user = userEvent.setup();
    const view = render(<SessionTtlPanel key="first" workspaceId="first" />);
    expect(await screen.findByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Enabled" }));
    await user.selectOptions(screen.getByRole("combobox"), "24");
    expect(api.sessionTtl.has("first")).toBe(false);
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Session settings saved.");
    view.rerender(<SessionTtlPanel key="second" workspaceId="second" />);
    expect(await screen.findByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("combobox")).toHaveValue("168");
    view.rerender(<SessionTtlPanel key="first" workspaceId="first" />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Enabled" })).toHaveAttribute("aria-pressed", "true"));
    expect(screen.getByRole("combobox")).toHaveValue("24");
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Disabled" }));
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    await waitFor(() => expect(api.sessionTtl.get("first")).toEqual({ sessionTtlEnabled: false, sessionTtlHours: 24 }));
    expect(localStorage.getItem("workspaced:session-ttl:first")).toBeNull();
  });

  it("retries load failures and keeps unsaved preferences when saving fails", async () => {
    const api = installFakeApi();
    api.state.fail = 503;
    const user = userEvent.setup();
    render(<SessionTtlPanel workspaceId="starter" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load session settings");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    await user.click(await screen.findByRole("button", { name: "Enabled" }));
    api.state.fail = 503;
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Could not save session settings");
    expect(screen.getByRole("button", { name: "Save session settings" })).toBeEnabled();
    expect(api.sessionTtl.has("starter")).toBe(false);
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Save session settings" }));
    await waitFor(() => expect(api.sessionTtl.get("starter")?.sessionTtlEnabled).toBe(true));
  });
});

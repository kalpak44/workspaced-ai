import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { UserMenu } from "../src/components/organisms/UserMenu";
import { installFakeApi } from "./fakeApi";

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
});

/** Opens the app on a phone inside Alpha, with Beta beside it. */
async function setup() {
  const api = installFakeApi([
    { id: "alpha", name: "Alpha", status: "READY", modelProvider: "demo", model: null, timeZone: "UTC", restartRequired: false },
    { id: "beta", name: "Beta", status: "READY", modelProvider: "demo", model: null, timeZone: "UTC", restartRequired: false },
  ]);
  globalThis.history.replaceState(null, "", "/workspaces/alpha");
  render(<App />);
  await screen.findByRole("region", { name: "Workspace details" });
  return { api, user: userEvent.setup(), trigger: screen.getByRole("button", { name: "Switch workspace" }) };
}

describe("mobile workspace navigation", () => {
  it("uses a modal for the account menu and keeps theme changes inside it", async () => {
    const user = userEvent.setup();
    const onThemeChange = vi.fn();
    render(<UserMenu theme="system" onThemeChange={onThemeChange} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const dialog = screen.getByRole("dialog", { name: "Account" });
    await user.click(within(dialog).getByRole("button", { name: "Dark" }));
    expect(onThemeChange).toHaveBeenCalledWith("dark");
    expect(dialog).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("menuitem", { name: "Settings" }));
    expect(location.pathname).toBe("/settings");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows the current workspace and switches, keeping creation separate", async () => {
    const { user, trigger } = await setup();
    expect(trigger).toHaveTextContent("Alpha");
    await user.click(trigger);
    await user.click(screen.getByRole("menuitem", { name: "Beta" }));
    expect(location.pathname).toBe("/workspaces/beta");
    expect(screen.getByRole("button", { name: "Switch workspace" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Switch workspace" }));
    await user.click(screen.getByRole("menuitem", { name: "All workspaces" }));
    expect(location.pathname).toBe("/");
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    expect(screen.getByRole("dialog", { name: "New workspace" })).toBeInTheDocument();
  });

  it("opens workspace actions in a sheet and renames from it", async () => {
    const { user, api } = await setup();
    await user.click(screen.getByRole("button", { name: "Actions for Alpha" }));
    const dialog = screen.getByRole("dialog", { name: "Alpha" });
    for (const name of ["Rename", "Settings", "Terminal", "Monitoring", "Restart", "Delete workspace"]) {
      expect(within(dialog).getByRole("menuitem", { name })).toBeInTheDocument();
    }
    await user.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Actions for Alpha" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("menuitem", { name: "Rename" }));
    const name = screen.getByRole("textbox", { name: "Workspace name" });
    expect(name).toHaveFocus();
    await user.clear(name);
    await user.type(name, "Renamed{Enter}");
    await waitFor(() => expect(api.rows[0].name).toBe("Renamed"));
  });

  it("dispatches an action from the sheet and closes it", async () => {
    const { user } = await setup();
    await user.click(screen.getByRole("button", { name: "Actions for Alpha" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("menuitem", { name: "Settings" }));
    expect(location.pathname).toBe("/workspaces/alpha/settings");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

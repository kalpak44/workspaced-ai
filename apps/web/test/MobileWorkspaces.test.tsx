import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Sidebar } from "../src/components/organisms/Sidebar";
import { UserMenu } from "../src/components/organisms/UserMenu";

beforeEach(() => {
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
});

function setup() {
  const handlers = { onSelect: vi.fn(), onNew: vi.fn(), onRename: vi.fn(), onSettings: vi.fn(), onTerminal: vi.fn(), onRestart: vi.fn(), onBackup: vi.fn(), onRestore: vi.fn(), onDelete: vi.fn() };
  render(<Sidebar workspaces={[
    { id: "alpha", name: "Alpha", status: "READY", modelProvider: "demo", model: null, restartRequired: false },
    { id: "beta", name: "Beta", status: "READY", modelProvider: "demo", model: null, restartRequired: false },
  ]} selectedId="alpha" deleting={new Set()} {...handlers} />);
  return { ...handlers, user: userEvent.setup(), trigger: screen.getByRole("button", { name: "Choose workspace" }) };
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

  it("shows the current workspace, searches, switches, and keeps creation separate", async () => {
    const { user, trigger, onSelect, onNew } = setup();
    expect(trigger).toHaveTextContent("Alpha");
    await user.click(trigger);
    await user.type(screen.getByRole("searchbox", { name: "Search workspaces" }), "BETA");
    expect(screen.queryByRole("button", { name: "Alpha" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Beta" }));
    expect(onSelect).toHaveBeenCalledWith("beta");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(onNew).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    expect(onNew).toHaveBeenCalledOnce();
  });

  it("opens workspace actions in a modal and keeps rename usable inside the dropdown", async () => {
    const { user, trigger, onRename } = setup();
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Actions for Alpha" }));
    const dialog = screen.getByRole("dialog", { name: "Alpha" });
    for (const name of ["Rename", "Settings", "Terminal", "Delete workspace"]) {
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
    expect(onRename).toHaveBeenCalledWith("alpha", "Renamed");
  });

  it("dispatches an action from the modal before dismissing the dropdown", async () => {
    const { user, trigger, onSettings } = setup();
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "Actions for Alpha" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("menuitem", { name: "Settings" }));
    expect(onSettings).toHaveBeenCalledWith("alpha");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { formatBytes } from "../src/lib/format";
import { parseRoute, routePath } from "../src/lib/routes";
import { groupBackups } from "../src/hooks/useBackups";
import { installFakeApi, newBackup, REJECTED_KEY } from "./fakeApi";

async function openApp(path: string) {
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}

afterEach(() => {
  vi.useRealTimers();
});

describe("backups", () => {
  it("routes to the backups page, with or without a workspace", () => {
    expect(parseRoute("/settings/backups")).toEqual({ name: "backups" });
    expect(parseRoute("/settings/backups/abc")).toEqual({ name: "backups", group: "abc" });
    expect(routePath({ name: "backups", group: "abc" })).toBe("/settings/backups/abc");
    expect(routePath({ name: "backups" })).toBe("/settings/backups");
  });

  it("formats archive sizes", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2_500_000)).toBe("2.5 MB");
    expect(formatBytes(12_000_000_000)).toBe("12 GB");
  });

  it("groups versions by workspace, newest group first", () => {
    const groups = groupBackups([
      newBackup({ id: "b3", workspaceId: "w2", workspaceName: "Two" }),
      newBackup({ id: "b2", workspaceId: "w1" }),
      newBackup({ id: "b1", workspaceId: "w2", workspaceName: "Two" }),
    ]);
    expect(groups.map((g) => [g.name, g.backups.map((b) => b.id)])).toEqual([
      ["Two", ["b3", "b1"]],
      ["Starter", ["b2"]],
    ]);
  });

  it("backs up a workspace from its menu and follows the progress", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Backup" }));
    const dialog = await screen.findByRole("dialog");
    await waitFor(() => expect(within(dialog).getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50"), { timeout: 3000 });
    expect(await within(dialog).findByText("Saved 2.5 MB", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(api.backups.list[0].status).toBe("COMPLETED");
    await user.click(within(dialog).getByRole("button", { name: "View backups" }));
    expect(location.pathname).toBe("/settings/backups/starter");
    expect(await screen.findByRole("list", { name: "Backup versions" })).toBeInTheDocument();
  });

  it("reports a backup the workspace could not start", async () => {
    const api = installFakeApi();
    api.backups.failStart = 409;
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Backup" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("needs a restart");
  });

  it("reports a backup that failed part way", async () => {
    const api = installFakeApi();
    api.backups.failRun = true;
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Backup" }));
    expect(await screen.findByText("disk full", {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it("opens the workspace's backups from Restore in its menu", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1", secrets: ["GITHUB_TOKEN"], sessions: 3, instructions: ["chat"] }));
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Restore" }));
    expect(location.pathname).toBe("/settings/backups/starter");
    expect(await screen.findByText(/3 conversations · 1 instruction/)).toBeInTheDocument();
    expect(screen.getByText("GITHUB_TOKEN")).toBeInTheDocument();
  });

  it("restores a version in place after confirming", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp("/settings/backups");
    await user.click(await screen.findByRole("button", { name: "Restore" }));
    await user.click(screen.getByRole("button", { name: "Restore workspace" }));
    await waitFor(() => expect(location.pathname).toBe("/workspaces/starter"));
    expect(api.backups.restored).toEqual(["b1"]);
  });

  it("creates a new workspace from a version, reusing the key while the original exists", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp("/settings/backups");
    await user.click(await screen.findByRole("button", { name: "Create new" }));
    expect(screen.getByLabelText("Name")).toHaveValue("Starter copy");
    await user.click(screen.getByRole("button", { name: "Create workspace" }));
    await waitFor(() => expect(location.pathname).toMatch(/^\/workspaces\/ws-/));
    expect(api.rows.some((w) => w.name === "Starter copy")).toBe(true);
  });

  it("asks for a key once the original workspace is gone, and shows a refused one", async () => {
    const api = installFakeApi([]);
    api.backups.list.push(newBackup({ id: "b1", workspaceId: "gone", workspaceName: "Old", secrets: ["TOKEN"] }));
    const user = userEvent.setup();
    await openApp("/settings/backups");
    expect(await screen.findByText("deleted")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Restore" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create new" }));
    expect(screen.getByRole("button", { name: "Create workspace" })).toBeDisabled();
    await user.type(screen.getByLabelText("API key"), REJECTED_KEY);
    await user.click(screen.getByRole("button", { name: "Create workspace" }));
    expect(await screen.findByText("The provider rejected this API key.")).toBeInTheDocument();
  });

  it("downloads a version through a signed link", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const open = vi.spyOn(globalThis, "open").mockReturnValue(null);
    const user = userEvent.setup();
    await openApp("/settings/backups");
    await user.click(await screen.findByRole("button", { name: "Download" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://storage/b1", "_blank", "noopener"));
    open.mockRestore();
  });

  it("deletes a version after confirming", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp("/settings/backups");
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Delete backup" }));
    expect(await screen.findByText("No backups yet")).toBeInTheDocument();
    expect(api.backups.list).toHaveLength(0);
  });

  it("shows how far a starting workspace is through its restore", async () => {
    const api = installFakeApi([{ id: "starter", name: "Starter", modelProvider: "openrouter", model: null, status: "INITIALIZING", restartRequired: false }]);
    api.backups.restoreProgress.set("starter", { phase: "extracting", done: 77492, total: 84048, progress: 94 });
    await openApp("/workspaces/starter");
    expect(await screen.findByText("Restoring files: 77,492 of 84,048")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Restore progress" })).toHaveAttribute("aria-valuenow", "94");
    api.backups.restoreProgress.set("starter", { phase: "downloading", done: 1_000_000, total: 2_000_000, progress: 10 });
    expect(await screen.findByText("Downloading the backup: 1.0 MB of 2.0 MB", {}, { timeout: 3000 })).toBeInTheDocument();
    api.backups.restoreProgress.set("starter", { phase: "checking", done: 1, total: 2, progress: 25 });
    expect(await screen.findByText(/Checking the archive/, {}, { timeout: 3000 })).toBeInTheDocument();
    api.backups.restoreProgress.set("starter", { phase: "done", done: 1, total: 1, progress: 100 });
    expect(await screen.findByText("Starting the agent…", {}, { timeout: 3000 })).toBeInTheDocument();
  }, 15000);

  it("shows the ordinary start when the workspace is not restoring", async () => {
    installFakeApi([{ id: "starter", name: "Starter", modelProvider: "openrouter", model: null, status: "INITIALIZING", restartRequired: false }]);
    await openApp("/workspaces/starter");
    expect(await screen.findByText("Starting the workspace")).toBeInTheDocument();
  });

  it("opens the backups page from the account menu", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Backups" }));
    expect(location.pathname).toBe("/settings/backups");
    expect(await screen.findByText("No backups yet")).toBeInTheDocument();
  });
});

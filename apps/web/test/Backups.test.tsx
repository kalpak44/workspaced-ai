import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { formatBytes } from "../src/lib/format";
import { parseRoute, routePath } from "../src/lib/routes";
import { groupBackups, PART_BYTES } from "../src/hooks/useBackups";
import { installFakeApi, newBackup, REJECTED_KEY } from "./fakeApi";

const BACKUPS = "/workspaces/starter/settings/backups";

async function openApp(path: string) {
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}

function archive(name: string, size: number) {
  const file = new File([new Uint8Array(0)], name, { type: "application/gzip" });
  // A real archive of this size would be slow to build in a test; slicing honours the claim.
  Object.defineProperty(file, "size", { value: size });
  file.slice = (start?: number, end?: number) => new Blob([new Uint8Array((end ?? size) - (start ?? 0))]);
  return file;
}

afterEach(() => {
  vi.useRealTimers();
});

describe("backups", () => {
  it("routes to a workspace's backups as a settings section", () => {
    expect(parseRoute(BACKUPS)).toEqual({ name: "settings", workspaceId: "starter", section: "backups" });
    expect(routePath({ name: "settings", workspaceId: "starter", section: "backups" })).toBe(BACKUPS);
    expect(routePath({ name: "settings", workspaceId: "starter" })).toBe("/workspaces/starter/settings");
    expect(parseRoute("/settings/backups")).toEqual({ name: "home" });
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

  it("opens the workspace's backups from its menu", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1", secrets: ["GITHUB_TOKEN"], sessions: 3, instructions: ["chat"] }));
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Actions for Starter" }));
    expect(screen.queryByRole("menuitem", { name: "Restore" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "Backups" }));
    expect(location.pathname).toBe(BACKUPS);
    expect(await screen.findByText(/3 conversations · 1 instruction/)).toBeInTheDocument();
    expect(screen.getByText("GITHUB_TOKEN")).toBeInTheDocument();
  });

  it("is no longer in the account menu", async () => {
    installFakeApi();
    const user = userEvent.setup();
    await openApp("/workspaces/starter");
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    expect(screen.queryByRole("menuitem", { name: "Backups" })).not.toBeInTheDocument();
  });

  it("backs up after confirming and shows the progress in the list", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openApp(BACKUPS);
    expect(await screen.findByText(/No backups yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Back up now" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Back up now" }));
    expect(await screen.findByRole("progressbar", { name: "Backup progress" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Back up now" })).toBeDisabled();
    expect(await screen.findByRole("button", { name: "Download" }, { timeout: 4000 })).toBeInTheDocument();
    expect(api.backups.list[0].status).toBe("COMPLETED");
  });

  it("reports a backup the workspace could not start", async () => {
    const api = installFakeApi();
    api.backups.failStart = 409;
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Back up now" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Back up now" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("needs a restart");
  });

  it("shows a backup that failed part way", async () => {
    const api = installFakeApi();
    api.backups.failRun = true;
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Back up now" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Back up now" }));
    expect(await screen.findByText("disk full", {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it("uploads an archive in parts and restores it", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Upload backup" }));
    await user.upload(screen.getByLabelText("Backup archive"), archive("home.tar.gz", PART_BYTES + 10));
    expect(screen.getByText(/Every file in the workspace is replaced/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Upload and restore" }));
    await waitFor(() => expect(api.backups.restored).toEqual(["backup-1"]));
    expect(api.backups.parts).toEqual([`backup-1:1:${PART_BYTES}`, "backup-1:2:10"]);
    expect(await screen.findByText("Uploaded")).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Restore" })).toHaveTextContent("Restoring the backup");
  });

  it("keeps an upload to restore later when asked to", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Upload backup" }));
    await user.upload(screen.getByLabelText("Backup archive"), archive("home.tgz", 10));
    await user.click(screen.getByLabelText(/Restore it once uploaded/));
    await user.click(screen.getByRole("button", { name: "Upload" }));
    expect(await screen.findByText("Uploaded")).toBeInTheDocument();
    expect(await screen.findByText(/files only/)).toBeInTheDocument();
    expect(api.backups.restored).toEqual([]);
  });

  it("refuses a file that is not an archive", async () => {
    const api = installFakeApi();
    const user = userEvent.setup({ applyAccept: false });
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Upload backup" }));
    await user.upload(screen.getByLabelText("Backup archive"), archive("home.zip", 10));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a .tar.gz or .tgz archive");
    expect(screen.getByRole("button", { name: "Upload and restore" })).toBeDisabled();
    api.backups.failPart = 400;
    await user.upload(screen.getByLabelText("Backup archive"), archive("renamed.tar.gz", 10));
    await user.click(screen.getByRole("button", { name: "Upload and restore" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("not a .tar.gz archive");
    await waitFor(() => expect(api.backups.list).toHaveLength(0));
    expect(api.backups.restored).toEqual([]);
  });

  it("restores a version in place after confirming", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Restore" }));
    await user.click(screen.getByRole("button", { name: "Restore workspace" }));
    expect(await screen.findByText("Restoring the backup")).toBeInTheDocument();
    expect(api.backups.restored).toEqual(["b1"]);
    expect(location.pathname).toBe(BACKUPS);
  });

  it("creates a new workspace from a version, reusing the key while the original exists", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Create new" }));
    expect(within(screen.getByRole("dialog")).getByLabelText("Name")).toHaveValue("Starter copy");
    await user.click(screen.getByRole("button", { name: "Create workspace" }));
    await waitFor(() => expect(location.pathname).toMatch(/^\/workspaces\/ws-/));
    expect(api.rows.some((w) => w.name === "Starter copy")).toBe(true);
  });

  it("lists a deleted workspace's backups, which can only start a new one", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1", workspaceId: "gone", workspaceName: "Old", secrets: ["TOKEN"] }));
    const user = userEvent.setup();
    await openApp(BACKUPS);
    const section = await screen.findByRole("region", { name: "Backups of deleted workspaces" });
    expect(within(section).getByText("Old")).toBeInTheDocument();
    expect(within(section).queryByRole("button", { name: "Restore" })).not.toBeInTheDocument();
    await user.click(within(section).getByRole("button", { name: "Create new" }));
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
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Download" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://storage/b1", "_blank", "noopener"));
    open.mockRestore();
  });

  it("deletes a version after confirming", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1" }));
    const user = userEvent.setup();
    await openApp(BACKUPS);
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Delete backup" }));
    expect(await screen.findByText(/No backups yet/)).toBeInTheDocument();
    expect(api.backups.list).toHaveLength(0);
  });

  it("cancels an interrupted upload after confirming", async () => {
    const api = installFakeApi();
    api.backups.list.push(newBackup({ id: "b1", status: "RUNNING", phase: "uploading", progress: 40, uploaded: true }));
    const user = userEvent.setup();
    await openApp(BACKUPS);
    expect(await screen.findByText("Uploading from another tab, or interrupted")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel upload" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel upload" }));
    await waitFor(() => expect(api.backups.list).toHaveLength(0));
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
});

import { render, renderHook, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { WorkspaceCard } from "../src/components/molecules";
import { CollaboratorsPanel } from "../src/components/organisms";
import { resetAccount } from "../src/hooks/useAccount";
import type { Workspace } from "../src/hooks/useWorkspaces";
import { useWorkspaces } from "../src/hooks/useWorkspaces";
import { captureInvitation, hasPendingInvitation } from "../src/lib/invitation";
import { installFakeApi } from "./fakeApi";

const MINE: Workspace = { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, timeZone: "UTC", status: "READY", restartRequired: false, ownerId: "admin-id" };
const SHARED: Workspace = { ...MINE, id: "shared", name: "Team", ownerId: "someone-else" };

const handlers = { onOpen: vi.fn(), onRename: vi.fn(), onSettings: vi.fn(), onTerminal: vi.fn(), onMonitoring: vi.fn(), onRestart: vi.fn(), onDelete: vi.fn() };

describe("workspace collaborators", () => {
  afterEach(() => {
    resetAccount();
    localStorage.clear();
    globalThis.history.replaceState(null, "", "/");
  });

  it("invites by email and shows each person's status", async () => {
    const api = installFakeApi([MINE]);
    api.collaborators.set("starter", [
      { userId: "ana-id", invitationId: null, email: "ana@example.com", status: "APPLIED", invitedBy: "admin@example.com", since: "2026-11-20T10:00:00Z", expiresAt: null },
    ]);
    const user = userEvent.setup();
    render(<CollaboratorsPanel workspace={MINE} onLeave={vi.fn()} />);

    const list = await screen.findByRole("list", { name: "Collaborators" });
    expect(within(list).getByText("admin@example.com")).toBeInTheDocument();
    expect(within(list).getByText("Owner")).toBeInTheDocument();
    expect(within(list).getByText("Applied")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Email address"), "bob@example.com");
    await user.click(screen.getByRole("button", { name: "Invite" }));

    expect(await screen.findByText(/Invitation sent to bob@example.com/)).toBeInTheDocument();
    expect(api.invited).toEqual([{ workspaceId: "starter", email: "bob@example.com" }]);
    // The address of an open invitation is not kept, so the row names none.
    const pending = await within(list).findByText("Pending");
    expect(within(pending.closest("li")!).getByText("Invitation by email")).toBeInTheDocument();
    expect(within(pending.closest("li")!).getByText(/expires in/)).toBeInTheDocument();
  });

  it("lets the owner remove a collaborator after confirming", async () => {
    const api = installFakeApi([MINE]);
    api.collaborators.set("starter", [
      { userId: "ana-id", invitationId: null, email: "ana@example.com", status: "APPLIED", invitedBy: null, since: null, expiresAt: null },
    ]);
    const user = userEvent.setup();
    render(<CollaboratorsPanel workspace={MINE} onLeave={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: "Remove ana@example.com" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Remove" }));

    await waitFor(() => expect(screen.queryByText("ana@example.com")).not.toBeInTheDocument());
    expect(api.collaborators.get("starter")).toEqual([]);
  });

  it("offers a collaborator only leaving, never removing anyone", async () => {
    const api = installFakeApi([SHARED]);
    api.collaborators.set("shared", [
      { userId: "admin-id", invitationId: null, email: "admin@example.com", status: "APPLIED", invitedBy: "owner@example.com", since: null, expiresAt: null },
      { userId: "ana-id", invitationId: null, email: "ana@example.com", status: "APPLIED", invitedBy: "owner@example.com", since: null, expiresAt: null },
    ]);
    const onLeave = vi.fn();
    const user = userEvent.setup();
    render(<CollaboratorsPanel workspace={SHARED} onLeave={onLeave} />);

    await screen.findByText("ana@example.com");
    expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Leave" }));
    expect(onLeave).toHaveBeenCalled();
  });

  it("sets a shared workspace's tile apart and offers leaving instead of deleting", async () => {
    installFakeApi([MINE, SHARED]);
    const user = userEvent.setup();
    render(<ul><WorkspaceCard workspace={MINE} deleting={false} {...handlers} /><WorkspaceCard workspace={SHARED} deleting={false} {...handlers} /></ul>);

    const [mine, shared] = screen.getAllByRole("listitem");
    await waitFor(() => expect(shared).toHaveAttribute("data-shared", "true"));
    expect(within(shared).getByText("Shared")).toBeInTheDocument();
    expect(mine).not.toHaveAttribute("data-shared");
    expect(within(mine).queryByText("Shared")).not.toBeInTheDocument();

    await user.click(within(shared).getByRole("button", { name: "Actions for Team" }));
    expect(screen.getByRole("menuitem", { name: "Invite people" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Leave workspace" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete workspace" })).not.toBeInTheDocument();
  });

  it("keeps an invitation link through sign-in, then accepts it and opens the workspace", async () => {
    const api = installFakeApi([MINE]);
    api.invitationCodes.set("code-123", "shared");
    globalThis.history.replaceState(null, "", "/invitation#code-123");
    captureInvitation();
    expect(globalThis.location.pathname).toBe("/");
    expect(hasPendingInvitation()).toBe(true);
    // Accepting adds the workspace to the list the API answers with.
    api.rows.push(SHARED);

    const hook = renderHook(() => useWorkspaces());

    await waitFor(() => expect(globalThis.location.pathname).toBe("/workspaces/shared"));
    expect(hook.result.current.workspaces.map((w) => w.id)).toEqual(["starter", "shared"]);
    expect(hasPendingInvitation()).toBe(false);
  });

  it("says why an expired invitation could not be accepted", async () => {
    installFakeApi([MINE]);
    globalThis.history.replaceState(null, "", "/invitation#gone");
    captureInvitation();

    const hook = renderHook(() => useWorkspaces());

    await waitFor(() => expect(hook.result.current.error).toMatch(/expired or was already used/));
  });

  it("asks before a collaborator leaves, and leaves only once confirmed", async () => {
    const api = installFakeApi([MINE, SHARED]);
    api.collaborators.set("shared", [
      { userId: "admin-id", invitationId: null, email: "admin@example.com", status: "APPLIED", invitedBy: "owner@example.com", since: null, expiresAt: null },
    ]);
    const user = userEvent.setup();
    render(<App />);

    const team = (await screen.findByRole("list", { name: "Workspaces" })).querySelector("[data-shared]") as HTMLElement;
    await user.click(within(team).getByRole("button", { name: "Actions for Team" }));
    await user.click(screen.getByRole("menuitem", { name: "Leave workspace" }));

    const dialog = screen.getByRole("dialog", { name: "Leave workspace" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(api.collaborators.get("shared")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Actions for Team" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Actions for Team" }));
    await user.click(screen.getByRole("menuitem", { name: "Leave workspace" }));
    await user.click(within(screen.getByRole("dialog", { name: "Leave workspace" })).getByRole("button", { name: "Leave" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "Actions for Team" })).not.toBeInTheDocument());
    expect(api.collaborators.get("shared")).toEqual([]);
    expect(screen.getByRole("button", { name: "Actions for Starter" })).toBeInTheDocument();
  });

  it("offers no sharing for a free demo", async () => {
    const demo: Workspace = { ...MINE, id: "demo", name: "Trial", demo: true };
    installFakeApi([demo]);
    const user = userEvent.setup();
    render(<ul><WorkspaceCard workspace={demo} deleting={false} {...handlers} /></ul>);

    await user.click(screen.getByRole("button", { name: "Actions for Trial" }));
    expect(screen.getByRole("menuitem", { name: "Delete workspace" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Invite people" })).not.toBeInTheDocument();
  });
});

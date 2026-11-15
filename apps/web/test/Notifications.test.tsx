import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { NotificationMenu } from "../src/components/organisms/NotificationMenu";
import { groupByDay, type Notification } from "../src/hooks/useNotifications";
import { installFakeApi } from "./fakeApi";

async function openMenu() {
  const user = userEvent.setup();
  render(<NotificationMenu />);
  await user.click(await screen.findByRole("button", { name: "Notifications, 4 unread" }));
  return { user, menu: screen.getByRole("menu") };
}

async function renderAt(path: string) {
  installFakeApi();
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}

describe("notifications", () => {
  it("shows the newest five under the bell and links to the rest", async () => {
    const { user, menu } = await openMenu();
    const items = within(menu).getAllByRole("menuitem").filter((item) => item.getAttribute("aria-label") === null && !/^(Mark all read|View all)/.test(item.textContent ?? ""));
    expect(items).toHaveLength(5);
    expect(items[0]).toHaveTextContent("Workspace is ready");
    expect(items[4]).toHaveTextContent("Scheduled task failed");
    await user.click(within(menu).getByRole("menuitem", { name: "View all notifications (7 more)" }));
    expect(location.pathname).toBe("/notifications");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("opens a notification from the menu and marks it read", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: /^Low balance/ }));
    expect(location.pathname).toBe("/notifications/n11");
    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
  });

  it("marks everything read from the menu", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark all read" }));
    expect(await screen.findByRole("button", { name: "Notifications" })).toBeInTheDocument();
    expect(within(menu).queryByText("4 new")).not.toBeInTheDocument();
  });

  it("toggles one notification read from its row without opening it", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark “Low balance” as read" }));
    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark “Low balance” as unread" }));
    expect(await screen.findByRole("button", { name: "Notifications, 4 unread" })).toBeInTheDocument();
    expect(location.pathname).toBe("/");
  });

  it("lists every notification and filters the unread ones", async () => {
    await renderAt("/notifications");
    const user = userEvent.setup();
    expect(await screen.findAllByRole("listitem")).toHaveLength(12);
    await user.click(screen.getByRole("tab", { name: "Unread (4)" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    await user.click(screen.getByRole("button", { name: "Billing" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    await user.click(screen.getByRole("tab", { name: "All" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    await user.click(screen.getByRole("button", { name: "Everything" }));
    await user.click(screen.getByRole("tab", { name: "Unread (4)" }));
    await user.click(screen.getByRole("button", { name: "Mark all read" }));
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument();
  });

  it("shows a notification in full and follows its action", async () => {
    await renderAt("/notifications/n10");
    const user = userEvent.setup();
    expect(await screen.findByRole("heading", { name: "Domain renews soon", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(/anyone may register it/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Manage domains" }));
    expect(location.pathname).toBe("/domains");
  });

  it("follows a notification's action straight from the list", async () => {
    await renderAt("/notifications");
    const user = userEvent.setup();
    const row = (await screen.findByText("Low balance")).closest("li")!;
    await user.click(within(row).getByRole("button", { name: "Go to billing" }));
    expect(location.pathname).toBe("/billing");
  });

  it("steps to older and newer notifications, and keeps one marked unread", async () => {
    await renderAt("/notifications/n12");
    const user = userEvent.setup();
    expect(await screen.findByRole("button", { name: "Newer notification" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Older notification" }));
    expect(location.pathname).toBe("/notifications/n11");
    expect(await screen.findByRole("heading", { name: "Low balance", level: 2 })).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Mark as unread" }));
    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mark as unread" })).not.toBeInTheDocument();
  });

  it("groups notifications into today, yesterday and earlier", () => {
    const at = (createdAt: string) => ({ id: createdAt, createdAt }) as Notification;
    const groups = groupByDay([at("2026-11-15T08:00:00"), at("2026-11-14T23:00:00"), at("2026-11-10T08:00:00")], new Date("2026-11-15T12:00:00"));
    expect(groups.map(([day, list]) => [day, list.length])).toEqual([["Today", 1], ["Yesterday", 1], ["Earlier", 1]]);
  });

  it("goes back to the list from a notification and says when one is missing", async () => {
    await renderAt("/notifications/gone");
    const user = userEvent.setup();
    expect(await screen.findByText("Notification not found")).toBeInTheDocument();
    await user.click(within(screen.getByRole("navigation", { name: "Breadcrumb" })).getByRole("button", { name: "Notifications" }));
    expect(location.pathname).toBe("/notifications");
  });
});

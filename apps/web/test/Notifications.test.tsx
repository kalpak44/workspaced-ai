import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { NotificationMenu } from "../src/components/organisms/NotificationMenu";
import { groupByDay, type Notification } from "../src/hooks/useNotifications";
import { installFakeApi } from "./fakeApi";

async function openMenu() {
  installFakeApi();
  const user = userEvent.setup();
  render(<NotificationMenu />);
  await user.click(await screen.findByRole("button", { name: "Notifications, 3 unread" }));
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
    expect(items[0]).toHaveTextContent("Workspace created");
    expect(items[4]).toHaveTextContent("Thank you for your payment");
    await user.click(within(menu).getByRole("menuitem", { name: "View all notifications (5 more)" }));
    expect(location.pathname).toBe("/notifications");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("opens a notification from the menu and marks it read", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: /^demo-box will be deleted/ }));
    expect(location.pathname).toBe("/notifications/n11");
    expect(await screen.findByRole("button", { name: "Notifications, 2 unread" })).toBeInTheDocument();
  });

  it("marks everything read from the menu", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark all read" }));
    expect(await screen.findByRole("button", { name: "Notifications" })).toBeInTheDocument();
    expect(within(menu).queryByText("3 new")).not.toBeInTheDocument();
  });

  it("toggles one notification read from its row without opening it", async () => {
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark “demo-box will be deleted in about 5 days” as read" }));
    expect(await screen.findByRole("button", { name: "Notifications, 2 unread" })).toBeInTheDocument();
    await user.click(within(menu).getByRole("menuitem", { name: "Mark “demo-box will be deleted in about 5 days” as unread" }));
    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    expect(location.pathname).toBe("/");
  });

  it("lists every notification and filters the unread ones", async () => {
    await renderAt("/notifications");
    const user = userEvent.setup();
    expect(await screen.findAllByRole("listitem")).toHaveLength(10);
    await user.click(screen.getByRole("tab", { name: "Unread (3)" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    await user.click(screen.getByRole("button", { name: "Billing" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    await user.click(screen.getByRole("tab", { name: "All" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    await user.click(screen.getByRole("button", { name: "Everything" }));
    await user.click(screen.getByRole("tab", { name: "Unread (3)" }));
    await user.click(screen.getByRole("button", { name: "Mark all read" }));
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument();
  });

  it("shows a notification in full and follows its action", async () => {
    await renderAt("/notifications/n9");
    const user = userEvent.setup();
    expect(await screen.findByRole("heading", { name: "New rating on your recipe", level: 2 })).toBeInTheDocument();
    expect(screen.getByText(/averages 4.6/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Notifications, 2 unread" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Open the store" }));
    expect(location.pathname).toBe("/store");
  });

  it("follows a notification's action straight from the list", async () => {
    await renderAt("/notifications");
    const user = userEvent.setup();
    const row = (await screen.findByText("demo-box will be deleted in about 5 days")).closest("li")!;
    await user.click(within(row).getByRole("button", { name: "Add funds" }));
    expect(location.pathname).toBe("/billing");
  });

  it("steps to older and newer notifications, and keeps one marked unread", async () => {
    await renderAt("/notifications/n12");
    const user = userEvent.setup();
    expect(await screen.findByRole("button", { name: "Newer notification" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Older notification" }));
    expect(location.pathname).toBe("/notifications/n11");
    expect(await screen.findByRole("heading", { name: "demo-box will be deleted in about 5 days", level: 2 })).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Mark as unread" }));
    expect(await screen.findByRole("button", { name: "Notifications, 2 unread" })).toBeInTheDocument();
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

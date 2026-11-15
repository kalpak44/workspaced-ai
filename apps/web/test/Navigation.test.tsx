import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { pageWindow } from "../src/components/molecules/Pagination";
import { FakeEventSource, installFakeApi } from "./fakeApi";

async function renderHome() {
  installFakeApi();
  render(<App />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Starter" }));
  await waitFor(() => expect(() => FakeEventSource.latest()).not.toThrow());
  return userEvent.setup();
}

describe("workspace header", () => {
  it("links the workspace's sessions, terminal, monitoring and settings as tabs", async () => {
    const user = await renderHome();
    const tabs = screen.getByRole("navigation", { name: "Workspace views" });
    expect(within(tabs).getByRole("link", { name: "Sessions" })).toHaveAttribute("aria-current", "page");

    await user.click(within(tabs).getByRole("link", { name: "Monitoring" }));
    expect(location.pathname).toBe("/workspaces/starter/monitoring");
    await screen.findByRole("list", { name: "Now" });

    await user.click(within(screen.getByRole("navigation", { name: "Workspace views" })).getByRole("link", { name: "Settings" }));
    expect(location.pathname).toBe("/workspaces/starter/settings");
  });
});

describe("command palette", () => {
  it("opens with the keyboard shortcut and jumps to the chosen page", async () => {
    const user = await renderHome();
    await user.keyboard("{Control>}k{/Control}");
    const palette = screen.getByRole("dialog", { name: "Command palette" });
    const search = within(palette).getByRole("combobox", { name: "Search commands" });
    expect(search).toHaveFocus();

    await user.type(search, "metrics");
    expect(within(palette).getAllByRole("option")[0]).toHaveTextContent("Monitoring");
    await user.keyboard("{Enter}");
    expect(screen.queryByRole("dialog", { name: "Command palette" })).not.toBeInTheDocument();
    expect(location.pathname).toBe("/workspaces/starter/monitoring");
  });

  it("moves through results with the arrows and says when nothing matches", async () => {
    const user = await renderHome();
    await user.click(screen.getByRole("button", { name: "Open command palette" }));
    const palette = screen.getByRole("dialog", { name: "Command palette" });
    const search = within(palette).getByRole("combobox", { name: "Search commands" });

    await user.type(search, "billing");
    await user.keyboard("{ArrowDown}{ArrowUp}{Enter}");
    expect(location.pathname).toBe("/billing");

    await user.click(screen.getByRole("button", { name: "Jump to" }));
    await user.type(screen.getByRole("combobox", { name: "Search commands" }), "zzzz");
    expect(screen.getByText("Nothing matches “zzzz”.")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Command palette" })).not.toBeInTheDocument();
  });

  it("starts a new workspace from the actions", async () => {
    const user = await renderHome();
    await user.keyboard("{Meta>}k{/Meta}");
    await user.click(screen.getByRole("option", { name: /New workspace/ }));
    expect(await screen.findByRole("dialog", { name: /workspace/i })).toBeInTheDocument();
  });
});

describe("page window", () => {
  it("shows every page up to seven", () => {
    expect(pageWindow(3, 5)).toEqual([1, 2, 3, 4, 5]);
  });

  it("keeps first, last and the current page's neighbours, with gaps between", () => {
    expect(pageWindow(1, 10)).toEqual([1, 2, 3, 4, 5, "gap", 10]);
    expect(pageWindow(5, 10)).toEqual([1, "gap", 4, 5, 6, "gap", 10]);
    expect(pageWindow(10, 10)).toEqual([1, "gap", 6, 7, 8, 9, 10]);
  });
});

describe("session search", () => {
  it("narrows the history by title and says when nothing matches", async () => {
    const api = installFakeApi();
    api.sessions.set(
      "starter",
      ["Deploy blog", "Fix login", "Deploy shop"].map((title, index) => ({
        id: `s-${index}`, title, path: `/p/s-${index}`, createdAt: new Date(Date.now() - index * 1000).toISOString(),
      })),
    );
    render(<App />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Starter" }));
    const list = await screen.findByRole("region", { name: "Sessions" });
    await within(list).findByText("Fix login");
    const user = userEvent.setup();

    await user.type(within(list).getByRole("searchbox", { name: "Search sessions" }), "deploy");
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).queryByText("Fix login")).not.toBeInTheDocument();

    await user.type(within(list).getByRole("searchbox", { name: "Search sessions" }), "zzz");
    expect(within(list).getByText("No sessions match “deployzzz”.")).toBeInTheDocument();
    await user.click(within(list).getByRole("button", { name: "Clear search" }));
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
  });
});

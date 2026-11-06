import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

/** The Users tab of the general settings, with its list loaded. */
async function openUsers() {
  globalThis.history.replaceState(null, "", "/settings");
  render(<App />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Users" }));
  return screen.findByRole("list", { name: "Users" });
}

/** Stands in for the browser's IntersectionObserver; `scrollToEnd` brings the end of the list into view. */
function stubIntersections() {
  const observers = new Set<IntersectionObserverCallback>();
  vi.stubGlobal("IntersectionObserver", class {
    readonly callback: IntersectionObserverCallback;
    constructor(callback: IntersectionObserverCallback) {
      this.callback = callback;
    }
    observe() {
      observers.add(this.callback);
    }
    disconnect() {
      observers.delete(this.callback);
    }
  });
  return {
    scrollToEnd: () => act(() => observers.forEach((callback) => callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))),
  };
}

function names(list: HTMLElement) {
  return within(list).getAllByRole("listitem").map((item) => item.querySelector("p")?.textContent);
}

describe("user management", () => {
  it("lists everyone, without a delete action for yourself", async () => {
    installFakeApi();
    const list = await openUsers();
    expect(within(list).getByText("admin")).toBeInTheDocument();
    expect(within(list).getByText("You")).toBeInTheDocument();
    expect(within(list).getByText("Grace Hopper")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete admin" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete grace" })).toBeInTheDocument();
  });

  it("creates a user in a dialog, and refuses a taken username or a short password", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const list = await openUsers();
    await user.click(screen.getByRole("button", { name: "Add user" }));
    const dialog = screen.getByRole("dialog", { name: "New user" });
    expect(within(dialog).getByLabelText("First name")).toHaveFocus();
    await user.type(within(dialog).getByLabelText("Username"), "grace");
    await user.type(within(dialog).getByLabelText("Password"), "short");
    await user.click(within(dialog).getByRole("button", { name: "Create user" }));
    expect(within(dialog).getByText("Use at least 8 characters.")).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText("Password"), "-but-long-now");
    await user.click(within(dialog).getByRole("button", { name: "Create user" }));
    expect(await within(dialog).findByText("grace is already taken.")).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("Username"));
    await user.type(within(dialog).getByLabelText("Username"), "ada");
    await user.type(within(dialog).getByLabelText("First name"), "Ada");
    await user.type(within(dialog).getByLabelText("Last name"), "Lovelace");
    await user.click(within(dialog).getByRole("button", { name: "Create user" }));
    expect(await within(list).findByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.users.others.map((u) => u.username)).toEqual(["grace", "ada"]);
  });

  it("creates nobody when the dialog is cancelled", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openUsers();
    await user.click(screen.getByRole("button", { name: "Add user" }));
    await user.type(screen.getByLabelText("Username"), "ada");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.users.others).toHaveLength(1);
  });

  it("deletes another user only once confirmed", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const list = await openUsers();
    await user.click(screen.getByRole("button", { name: "Delete grace" }));
    expect(screen.getByRole("dialog", { name: "Delete user?" })).toHaveTextContent("grace");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.users.others).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Delete grace" }));
    await user.click(screen.getByRole("button", { name: "Delete user" }));
    await waitFor(() => expect(within(list).queryByText("Grace Hopper")).not.toBeInTheDocument());
    expect(api.users.others).toEqual([]);
  });

  it("keeps a user it could not delete and says so", async () => {
    const api = installFakeApi();
    api.users.failDelete = true;
    const user = userEvent.setup();
    const list = await openUsers();
    await user.click(screen.getByRole("button", { name: "Delete grace" }));
    await user.click(screen.getByRole("button", { name: "Delete user" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not delete grace");
    expect(within(list).getByText("Grace Hopper")).toBeInTheDocument();
  });

  it("sorts the list in the order chosen, newest first by default", async () => {
    const api = installFakeApi();
    api.users.others.push({ id: "alan-id", username: "turing", firstName: "Alan", lastName: "Turing", avatar: null });
    const user = userEvent.setup();
    const list = await openUsers();
    expect(names(list)).toEqual(["Alan Turing", "Grace Hopper", "admin"]);
    expect(api.users.listed.at(-1)).toContain("sort=createdAt%2Cdesc");

    await user.selectOptions(screen.getByLabelText("Sort by"), "Name A–Z");
    await waitFor(() => expect(names(screen.getByRole("list", { name: "Users" }))).toEqual(["admin", "Alan Turing", "Grace Hopper"]));
    expect(api.users.listed.at(-1)).toContain("sort=firstName%2Casc&sort=lastName%2Casc");

    await user.selectOptions(screen.getByLabelText("Sort by"), "Username Z–A");
    await waitFor(() => expect(names(screen.getByRole("list", { name: "Users" }))).toEqual(["Alan Turing", "Grace Hopper", "admin"]));
  });

  it("loads the next page as the end of the list scrolls into view", async () => {
    const api = installFakeApi();
    api.users.others = Array.from({ length: 44 }, (_, i) => ({ id: `u${i}`, username: `user${String(i).padStart(2, "0")}`, firstName: "", lastName: "", avatar: null }));
    const { scrollToEnd } = stubIntersections();
    const user = userEvent.setup();
    const list = await openUsers();
    expect(within(list).getAllByRole("listitem")).toHaveLength(20);
    expect(screen.getByText("Showing 20 of 45")).toBeInTheDocument();

    scrollToEnd();
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(40));

    // A user deleted meanwhile shifts the rest up a place; the next page still misses none.
    await user.click(screen.getByRole("button", { name: "Delete user43" }));
    await user.click(screen.getByRole("button", { name: "Delete user" }));
    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(39));
    scrollToEnd();
    await waitFor(() => expect(screen.getByText("Showing 40 of 44")).toBeInTheDocument());
    scrollToEnd();
    await waitFor(() => expect(screen.getByText("Showing 44 of 44")).toBeInTheDocument());
    const shown = names(list);
    expect(new Set(shown).size).toBe(44);
    expect(shown.at(-1)).toBe("admin");
    expect(api.users.listed.filter((url) => url.includes("page=1"))).toHaveLength(2);
  });
});

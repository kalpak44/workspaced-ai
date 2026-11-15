import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { UserMenu } from "../src/components/organisms/UserMenu";
import { parseDomain } from "../src/hooks/useDomains";
import { installFakeApi } from "./fakeApi";

async function renderDomains() {
  installFakeApi();
  globalThis.history.replaceState(null, "", "/domains");
  render(<App />);
  await screen.findByRole("list", { name: "Your domains" });
}

async function searchFor(name: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Domain name"), name);
  await user.click(screen.getByRole("button", { name: "Search" }));
  return user;
}

describe("domains", () => {
  it("opens the domains page from the account menu", async () => {
    const user = userEvent.setup();
    render(<UserMenu theme="light" onThemeChange={() => {}} />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Domains" }));
    expect(location.pathname).toBe("/domains");
  });

  it("lists the domains the account owns", async () => {
    await renderDomains();
    const owned = screen.getByRole("list", { name: "Your domains" });
    expect(within(owned).getByText("pavel-labs.dev")).toBeInTheDocument();
    expect(within(owned).getByText("Auto-renew")).toBeInTheDocument();
  });

  it("links each owned domain to its DNS settings", async () => {
    await renderDomains();
    const user = userEvent.setup();
    const [first] = within(screen.getByRole("list", { name: "Your domains" })).getAllByRole("link", { name: /^Manage DNS for / });
    await user.click(first);
    expect(location.pathname).toBe("/settings/domains");
    expect(await screen.findByRole("navigation", { name: "Settings sections" })).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Settings sections" })).getByRole("button", { name: "Domains" })).toHaveAttribute("aria-current", "page");
  });

  it("refuses a name a registry would not accept", async () => {
    await renderDomains();
    await searchFor("-bad-");
    expect(screen.getByRole("alert")).toHaveTextContent("Use letters, digits and hyphens");
    expect(screen.queryByRole("list", { name: "Search results" })).not.toBeInTheDocument();
  });

  it("marks owned and taken names, and offers the free ones", async () => {
    await renderDomains();
    await searchFor("Pavel-Labs.com");
    const results = await screen.findByRole("list", { name: "Search results" });
    expect(within(results).getAllByRole("listitem")).toHaveLength(6);
    expect(within(results).queryByRole("button", { name: "Buy pavel-labs.dev" })).not.toBeInTheDocument();
    expect(within(results).queryByRole("button", { name: "Buy pavel-labs.app" })).not.toBeInTheDocument();
    expect(within(results).getByRole("button", { name: "Buy pavel-labs.com" })).toBeInTheDocument();
  });

  it("sends a buyer the balance does not cover to add funds", async () => {
    await renderDomains();
    const user = await searchFor("pavel-labs");
    await user.click(await screen.findByRole("button", { name: "Buy pavel-labs.com" }));
    expect(within(screen.getByRole("dialog")).getByText("€12.99 / year")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("does not cover");
    await user.click(screen.getByRole("button", { name: "Add funds" }));
    expect(location.pathname).toBe("/billing");
  });

  it("confirms a purchase the balance covers and registers nothing yet", async () => {
    await renderDomains();
    const user = await searchFor("studio");
    await user.click(await screen.findByRole("button", { name: "Buy studio.online" }));
    await user.click(await screen.findByRole("button", { name: "Buy for €3.99" }));
    expect(await screen.findByText(/studio\.online was not registered/)).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Your domains" })).not.toHaveTextContent("studio.online");
  });

  it("checks any domain as typed before suggesting other endings", async () => {
    await renderDomains();
    await searchFor("shop.co.uk");
    const items = within(await screen.findByRole("list", { name: "Search results" })).getAllByRole("listitem");
    expect(items).toHaveLength(7);
    expect(items[0]).toHaveTextContent("shop.co.uk");
    expect(items[1]).toHaveTextContent("shop.com");
  });

  it("reads a domain out of whatever is typed around it", () => {
    expect(parseDomain(" https://www.Acme.io/pricing ")).toEqual({ label: "acme", tld: "io" });
    expect(parseDomain("acme")).toEqual({ label: "acme", tld: null });
    expect(parseDomain("acme.c0m")).toBeNull();
  });
});

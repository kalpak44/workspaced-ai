import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { contentError } from "../src/hooks/useDnsRecords";
import { installFakeApi } from "./fakeApi";

// The sample zone is kept in memory for the whole file, so each test uses names of its own.
async function openDomainSettings() {
  installFakeApi();
  globalThis.history.replaceState(null, "", "/settings");
  render(<App />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Domains" }));
  const panel = await screen.findByRole("region", { name: "pavel-labs.dev" });
  return { user, panel };
}

describe("domain settings", () => {
  it("comes right after the profile and shows one panel per owned domain", async () => {
    await openDomainSettings();
    const nav = screen.getByRole("navigation", { name: "Settings sections" });
    expect(within(nav).getAllByRole("button").map((b) => b.textContent)).toEqual(["Profile", "Domains", "Users"]);
    expect(screen.getByRole("region", { name: "workspaced-demo.online" })).toBeInTheDocument();
  });

  it("creates and removes a subdomain in simple mode", async () => {
    const { user, panel } = await openDomainSettings();
    const subdomains = within(panel).getByRole("list", { name: "Subdomains of pavel-labs.dev" });
    expect(within(subdomains).getByText("blog.pavel-labs.dev")).toBeInTheDocument();
    expect(within(subdomains).queryByText("@.pavel-labs.dev")).not.toBeInTheDocument();

    await user.type(within(panel).getByLabelText("New subdomain"), "blog");
    await user.click(within(panel).getByRole("button", { name: "Create" }));
    expect(within(panel).getByText("blog.pavel-labs.dev already exists.")).toBeInTheDocument();

    await user.clear(within(panel).getByLabelText("New subdomain"));
    await user.type(within(panel).getByLabelText("New subdomain"), "Shop");
    await user.click(within(panel).getByRole("button", { name: "Create" }));
    expect(await within(panel).findByText("shop.pavel-labs.dev")).toBeInTheDocument();
    await user.click(within(panel).getByRole("button", { name: "Remove shop.pavel-labs.dev" }));
    expect(within(panel).queryByText("shop.pavel-labs.dev")).not.toBeInTheDocument();
  });

  it("lists, adds, edits and deletes every record in advanced mode", async () => {
    const { user, panel } = await openDomainSettings();
    await user.click(within(panel).getByRole("tab", { name: "Advanced" }));
    const table = within(panel).getByRole("table", { name: "Records of pavel-labs.dev" });
    expect(within(table).getByText("v=spf1 mx -all")).toBeInTheDocument();

    await user.click(within(panel).getByRole("button", { name: "Add record" }));
    const dialog = screen.getByRole("dialog", { name: "Add record" });
    await user.selectOptions(within(dialog).getByLabelText("Type"), "MX");
    await user.type(within(dialog).getByLabelText("Name"), "lists");
    await user.type(within(dialog).getByLabelText("Mail server"), "not a host");
    await user.click(within(dialog).getByRole("button", { name: "Add record" }));
    expect(within(dialog).getByText(/Enter a hostname/)).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText("Mail server"));
    await user.type(within(dialog).getByLabelText("Mail server"), "mx.lists.example.com");
    await user.click(within(dialog).getByRole("button", { name: "Add record" }));
    expect(await within(table).findByText("mx.lists.example.com")).toBeInTheDocument();

    await user.click(within(table).getByRole("button", { name: "Edit MX lists" }));
    const edit = screen.getByRole("dialog", { name: "Edit record" });
    await user.clear(within(edit).getByLabelText("Mail server"));
    await user.type(within(edit).getByLabelText("Mail server"), "mx2.lists.example.com");
    await user.click(within(edit).getByRole("button", { name: "Save record" }));
    expect(await within(table).findByText("mx2.lists.example.com")).toBeInTheDocument();

    await user.click(within(table).getByRole("button", { name: "Delete MX lists" }));
    expect(within(table).queryByText("mx2.lists.example.com")).not.toBeInTheDocument();
  });

  it("shows a record added in advanced mode as a subdomain in simple mode", async () => {
    const { user, panel } = await openDomainSettings();
    await user.click(within(panel).getByRole("tab", { name: "Advanced" }));
    await user.click(within(panel).getByRole("button", { name: "Add record" }));
    const dialog = screen.getByRole("dialog", { name: "Add record" });
    await user.type(within(dialog).getByLabelText("Name"), "vpn");
    await user.type(within(dialog).getByLabelText("IPv4 address"), "198.51.100.7");
    await user.click(within(dialog).getByRole("button", { name: "Add record" }));
    await user.click(within(panel).getByRole("tab", { name: "Simple" }));
    expect(await within(panel).findByText("vpn.pavel-labs.dev")).toBeInTheDocument();
    expect(within(panel).getByText("→ 198.51.100.7")).toBeInTheDocument();
  });

  it("checks record content against its type", () => {
    expect(contentError("A", "203.0.113.10")).toBeNull();
    expect(contentError("A", "256.0.0.1")).toMatch(/IPv4/);
    expect(contentError("AAAA", "2001:db8::1")).toBeNull();
    expect(contentError("CNAME", "edge.workspaced.online")).toBeNull();
    expect(contentError("TXT", "")).toBe("Enter a value.");
  });
});

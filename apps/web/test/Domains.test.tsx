import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { DomainsPanel } from "../src/components/organisms/DomainsPanel";
import { installFakeApi } from "./fakeApi";

async function add(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole("button", { name: "Add domain" }));
  const dialog = screen.getByRole("dialog", { name: "Add a domain" });
  await user.type(within(dialog).getByLabelText("Domain"), name);
  await user.click(within(dialog).getByRole("button", { name: "Add domain" }));
  return dialog;
}

describe("workspace domains", () => {
  afterEach(() => globalThis.history.replaceState(null, "", "/"));

  it("adds a domain and shows the records to add by hand", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<DomainsPanel workspaceId="starter" />);

    expect(await screen.findByText(/No domains yet/)).toBeInTheDocument();
    await add(user, "example.com");

    const item = within(await screen.findByRole("list", { name: "Domains" })).getAllByRole("listitem")[0];
    expect(within(item).getByText("example.com")).toBeInTheDocument();
    expect(within(item).getByText("Waiting for DNS")).toBeInTheDocument();
    const records = within(item).getByRole("list", { name: "DNS records" });
    expect(within(records).getAllByRole("listitem")).toHaveLength(3);
    expect(within(records).getByText("203.0.113.10")).toBeInTheDocument();
    expect(within(records).getByText("vm-1.vm.workspaced.online")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("cancels a request typed wrong without asking", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<DomainsPanel workspaceId="starter" />);

    await add(user, "app.exmaple.com");
    await user.click(await screen.findByRole("button", { name: "Cancel app.exmaple.com" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Cancelled app.exmaple.com.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(api.domains.get("starter")).toEqual([]);
    expect(screen.getByText(/No domains yet/)).toBeInTheDocument();
  });

  it("sends the user to a provider that adds the records itself", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<DomainsPanel workspaceId="starter" />);

    await add(user, "shop.connected.example");

    const connect = await screen.findByRole("link", { name: "Connect at GoDaddy" });
    expect(connect).toHaveAttribute("href", "https://dcc.godaddy.example/apply?domain=connected.example");
    expect(screen.getByText("Or add the records yourself")).toBeInTheDocument();
  });

  it("keeps what was typed and says why a name was refused", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<DomainsPanel workspaceId="starter" />);

    const dialog = await add(user, "not a domain");

    expect(await within(dialog).findByText(/enter a domain such as example.com/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Domain")).toHaveValue("not a domain");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows a live domain, opens the web ports and removes it with the records to clean up", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    const { unmount } = render(<DomainsPanel workspaceId="starter" />);
    await add(user, "blog.example.com");
    await screen.findByText("Waiting for DNS");
    unmount();
    api.domains.set("starter", api.domains.get("starter")!.map((d) => ({ ...d, status: "ACTIVE", connectUrl: null })));
    render(<DomainsPanel workspaceId="starter" />);

    expect(await screen.findByText("Live")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "DNS records" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Open ports 80 and 443" }));
    await waitFor(() => expect(api.firewalls.get("starter")?.rules.map((r) => r.port)).toEqual(["80", "443"]));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Open ports 80 and 443" })).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Remove blog.example.com" }));
    await user.click(within(screen.getByRole("dialog", { name: "Remove blog.example.com?" })).getByRole("button", { name: "Remove domain" }));

    const leftover = await screen.findByRole("dialog", { name: "Remove these records" });
    expect(within(leftover).getByText("vm-1.vm.workspaced.online")).toBeInTheDocument();
    await user.click(within(leftover).getByRole("button", { name: "Done" }));
    expect(api.domains.get("starter")).toEqual([]);
  });

  it("says when the DNS provider did not add the records", async () => {
    installFakeApi();
    globalThis.history.replaceState(null, "", "/workspaces/starter/settings/domains?state=d1&error=access_denied&error_description=Declined");
    render(<DomainsPanel workspaceId="starter" />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Your DNS provider did not add the records: Declined.");
    expect(globalThis.location.search).toBe("");
  });

  it("retries a failed load", async () => {
    const api = installFakeApi();
    api.state.fail = 503;
    const user = userEvent.setup();
    render(<DomainsPanel workspaceId="starter" />);

    expect(await screen.findByText("Could not load the domains.")).toBeInTheDocument();
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText(/No domains yet/)).toBeInTheDocument();
  });
});

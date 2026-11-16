import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { FirewallPanel } from "../src/components/organisms/FirewallPanel";
import { installFakeApi } from "./fakeApi";

describe("workspace firewall", () => {
  it("shows the address and opens and closes ports", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<FirewallPanel workspaceId="starter" />);

    expect(await screen.findByText("203.0.113.10")).toBeInTheDocument();
    expect(screen.getByText("No ports are open.")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Port"), "8080");
    await user.type(screen.getByLabelText("Note"), "Blog");
    await user.click(screen.getByRole("button", { name: "Open port" }));
    const rules = await screen.findByRole("list", { name: "Firewall rules" });
    expect(within(rules).getByText("TCP 8080")).toBeInTheDocument();
    expect(within(rules).getByText("Anyone · Blog")).toBeInTheDocument();
    expect(api.firewalls.get("starter")?.rules).toEqual([
      { protocol: "tcp", port: "8080", sourceIps: [], description: "Blog" },
    ]);

    await user.selectOptions(screen.getByLabelText("Protocol"), "icmp");
    expect(screen.getByLabelText("Port")).toBeDisabled();
    await user.type(screen.getByLabelText("From"), "10.0.0.0/8, 192.0.2.7");
    await user.click(screen.getByRole("button", { name: "Open port" }));
    expect(await screen.findByText("10.0.0.0/8, 192.0.2.7")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Remove TCP 8080" }));
    await waitFor(() => expect(api.firewalls.get("starter")?.rules).toHaveLength(1));
    expect(api.firewalls.get("starter")?.rules[0].protocol).toBe("icmp");
  });

  it("shows why a rule was refused and keeps what was typed", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<FirewallPanel workspaceId="starter" />);

    await user.type(await screen.findByLabelText("Port"), "0");
    await user.click(screen.getByRole("button", { name: "Open port" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("ports run from 1 to 65535");
    expect(screen.getByLabelText("Port")).toHaveValue("0");
  });

  it("saves the agent's permission", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<FirewallPanel workspaceId="starter" />);

    const save = await screen.findByRole("button", { name: "Save agent permission" });
    expect(save).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /Let the agent open and close ports/ }));
    await user.click(save);

    expect(await screen.findByRole("status")).toHaveTextContent("Agent permission saved.");
    expect(api.firewalls.get("starter")?.agentAllowed).toBe(false);
    expect(save).toBeDisabled();
  });

  it("retries a failed load", async () => {
    const api = installFakeApi();
    api.state.fail = 503;
    const user = userEvent.setup();
    render(<FirewallPanel workspaceId="starter" />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the firewall.");
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("203.0.113.10")).toBeInTheDocument();
  });
});

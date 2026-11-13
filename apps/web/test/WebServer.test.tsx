import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { WebServerPanel } from "../src/components/organisms/WebServerPanel";
import { installFakeApi } from "./fakeApi";

describe("workspace web server settings", () => {
  it("turns the web server on under a name, renames it and turns it off", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    expect(await screen.findByRole("button", { name: "Disabled" })).toHaveAttribute("aria-pressed", "true");
    const field = screen.getByRole("textbox", { name: "Name" });
    expect(field).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save web server" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Enabled" }));
    await user.type(field, "My-App");
    expect(field).toHaveValue("my-app");
    expect(screen.getByText("https://my-app.workspaced.test")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Web server saved.");
    expect(screen.getByRole("link", { name: "https://my-app.workspaced.test" })).toHaveAttribute("href", "https://my-app.workspaced.test");
    expect(api.webServers.get("starter")).toBe("my-app");
    expect(screen.getByRole("button", { name: "Save web server" })).toBeDisabled();

    await user.clear(field);
    await user.type(field, "renamed");
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    await waitFor(() => expect(api.webServers.get("starter")).toBe("renamed"));

    await user.click(screen.getByRole("button", { name: "Disabled" }));
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Web server turned off.");
    expect(api.webServers.has("starter")).toBe(false);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("explains a name that is invalid, taken, or needs the workspace running", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    await user.click(await screen.findByRole("button", { name: "Enabled" }));
    const field = screen.getByRole("textbox", { name: "Name" });

    await user.type(field, "-bad");
    expect(field).toHaveAccessibleDescription(/lowercase letters, digits and hyphens/);
    expect(screen.getByRole("button", { name: "Save web server" })).toBeDisabled();

    await user.clear(field);
    await user.type(field, "taken");
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("taken is taken or reserved");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    api.state.harnessDown = true;
    await user.clear(field);
    await user.type(field, "free");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Start the workspace first");
    expect(api.webServers.has("starter")).toBe(false);
  });

  it("lets the agent change the web server only once allowed, even while the workspace is stopped", async () => {
    const api = installFakeApi();
    api.state.harnessDown = true;
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    const allow = await screen.findByRole("checkbox", { name: /Let the agent turn it on, off and rename it/ });
    expect(allow).not.toBeChecked();

    await user.click(allow);
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Web server saved.");
    expect(api.webServerAgents.has("starter")).toBe(true);
    expect(api.webServers.has("starter")).toBe(false);
    expect(screen.getByRole("button", { name: "Save web server" })).toBeDisabled();

    await user.click(allow);
    await user.click(screen.getByRole("button", { name: "Save web server" }));
    await waitFor(() => expect(api.webServerAgents.has("starter")).toBe(false));
  });

  it("retries a failed load", async () => {
    const api = installFakeApi();
    api.state.fail = 503;
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the web server");
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("textbox", { name: "Name" })).toBeInTheDocument();
  });
});

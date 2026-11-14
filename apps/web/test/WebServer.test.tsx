import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { WebServerPanel } from "../src/components/organisms/WebServerPanel";
import { installFakeApi } from "./fakeApi";

describe("workspace web server settings", () => {
  it("validates names and explains conflicts or an unavailable workspace", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    const field = await screen.findByRole("textbox", { name: "New address" });
    await waitFor(() => expect(field).toBeEnabled());
    await user.type(field, "-bad");
    expect(screen.getByRole("button", { name: "Add address" })).toBeDisabled();
    expect(field).toHaveAttribute("aria-invalid", "true");
    await user.clear(field); await user.type(field, "taken");
    await user.click(screen.getByRole("button", { name: "Add address" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("taken");
    await user.clear(field); await user.type(field, "free");
    api.state.harnessDown = true;
    await user.click(screen.getByRole("button", { name: "Add address" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("workspace is running");
  });

  it("saves agent permission even while the workspace is stopped", async () => {
    const api = installFakeApi(); api.state.harnessDown = true;
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    const allow = await screen.findByRole("checkbox", { name: /Let the agent manage/ });
    await user.click(allow);
    await user.click(screen.getByRole("button", { name: "Save agent permission" }));
    expect(await screen.findByText("Agent permission saved.")).toBeInTheDocument();
    expect(api.webServerAgents.has("starter")).toBe(true);
    expect(screen.getByRole("button", { name: "Save agent permission" })).toBeDisabled();
    await user.click(allow);
    await user.click(screen.getByRole("button", { name: "Save agent permission" }));
    await waitFor(() => expect(api.webServerAgents.has("starter")).toBe(false));
  });

  it("retries a failed load", async () => {
    const api = installFakeApi(); api.state.fail = 503;
    const user = userEvent.setup(); render(<WebServerPanel workspaceId="starter" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the web server");
    api.state.fail = 0;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("textbox", { name: "New address" })).toBeInTheDocument();
  });

  it("adds multiple addresses and disables or removes one without changing the others", async () => {
    installFakeApi();
    const user = userEvent.setup();
    render(<WebServerPanel workspaceId="starter" />);
    const field = await screen.findByRole("textbox", { name: "New address" });
    await waitFor(() => expect(field).toBeEnabled());
    await user.type(field, "site");
    await user.click(screen.getByRole("button", { name: "Add address" }));
    expect(await screen.findByRole("button", { name: "Disable site" })).toBeEnabled();
    await user.type(field, "api");
    await user.click(screen.getByRole("button", { name: "Add address" }));
    expect(await screen.findByRole("button", { name: "Disable api" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Disable site" }));
    expect(await screen.findByRole("button", { name: "Enable site" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Disable api" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Remove site" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Enable site" })).not.toBeInTheDocument());
    expect(screen.getByRole("link", { name: "https://box-api.workspaced.test/" })).toHaveAttribute("href", "https://box-api.workspaced.test/");
  });

});

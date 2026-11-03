import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
});

const heading = () => screen.getByRole("heading", { level: 1 });
const sidebar = () => screen.getByRole("list");

describe("App", () => {
  it("opens on the starter workspace, empty", () => {
    render(<App />);
    expect(heading()).toHaveTextContent("Starter");
    expect(screen.getByText("Empty workspace")).toBeInTheDocument();
  });

  it("creates numbered workspaces from either button and selects the new one", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.click(within(screen.getByRole("main")).getByRole("button", { name: "New Workspace" }));
    expect(within(sidebar()).getByText("New Workspace")).toBeInTheDocument();
    expect(heading()).toHaveTextContent("New Workspace 2");
  });

  it("renames from the item menu, and Escape cancels a rename", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    const input = screen.getByRole("textbox", { name: "Workspace name" });
    expect(input).toHaveFocus();
    await user.clear(input);
    await user.type(input, "Research{Enter}");
    expect(heading()).toHaveTextContent("Research");

    await user.click(screen.getByRole("button", { name: "Actions for Research" }));
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    await user.type(screen.getByRole("textbox", { name: "Workspace name" }), "x{Escape}");
    expect(heading()).toHaveTextContent("Research");
  });

  it("deletes down to the no-workspaces state", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete" }));
    expect(screen.getByText("No workspaces")).toBeInTheDocument();
  });

  it("closes an item menu on an outside click", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
    await user.click(heading());
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("imports a workspace named after the file", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Import workspace" }));
    await user.upload(
      screen.getByTestId("import-input"),
      new File(["{}"], "Imported.json", { type: "application/json" }),
    );
    expect(heading()).toHaveTextContent("Imported");
  });

  it("switches to Knowledge and My Files, and back to a workspace", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Knowledge" }));
    expect(screen.getByText("No knowledge yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "My Files" }));
    expect(screen.getByText("No files yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Starter" }));
    expect(heading()).toHaveTextContent("Starter");
  });

  it("shows the version, the API status and the theme switch in the account menu", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    const menu = screen.getByRole("menu");
    expect(within(menu).getByText("dev")).toBeInTheDocument();
    await waitFor(() => expect(within(menu).getByText("ready")).toBeInTheDocument());
    expect(within(menu).getByRole("menuitem", { name: "Docs" })).toHaveAttribute(
      "href",
      "/api/v1/swagger-ui",
    );

    await user.click(within(menu).getByRole("button", { name: "Light" }));
    expect(within(menu).getByRole("button", { name: "Light" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    await user.click(within(menu).getByRole("menuitem", { name: "Settings" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("closes the account menu on Escape and from Logout", async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Logout" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows an unreachable API", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await waitFor(() => expect(screen.getByText("unreachable")).toBeInTheDocument());
  });
});

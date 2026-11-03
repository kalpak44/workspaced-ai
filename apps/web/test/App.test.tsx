import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

let api: ReturnType<typeof installFakeApi>;

beforeEach(() => {
  api = installFakeApi();
});

/** Renders and waits until the workspaces have arrived from the (fake) API. */
async function renderLoaded() {
  render(<App />);
  await screen.findByRole("button", { name: "Starter" });
}

const heading = () => screen.getByRole("heading", { level: 1 });
const sidebar = () => screen.getByRole("list");

describe("App", () => {
  it("opens on the first workspace from the API, empty", async () => {
    await renderLoaded();
    expect(heading()).toHaveTextContent("Starter");
    expect(screen.getByText("Empty workspace")).toBeInTheDocument();
  });

  it("creates numbered workspaces from either button and selects the new one", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.click(within(screen.getByRole("main")).getByRole("button", { name: "New Workspace" }));
    expect(await within(sidebar()).findByText("New Workspace 2")).toBeInTheDocument();
    expect(heading()).toHaveTextContent("New Workspace 2");
    expect(api.rows.map((w) => w.name)).toEqual(["Starter", "New Workspace", "New Workspace 2"]);
  });

  it("renames from the item menu, and Escape cancels a rename", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    const input = screen.getByRole("textbox", { name: "Workspace name" });
    expect(input).toHaveFocus();
    await user.clear(input);
    await user.type(input, "Research{Enter}");
    await waitFor(() => expect(heading()).toHaveTextContent("Research"));
    expect(api.rows).toEqual([{ id: "starter", name: "Research" }]);

    await user.click(screen.getByRole("button", { name: "Actions for Research" }));
    await user.click(screen.getByRole("menuitem", { name: "Rename" }));
    await user.type(screen.getByRole("textbox", { name: "Workspace name" }), "x{Escape}");
    expect(heading()).toHaveTextContent("Research");
  });

  it("deletes down to the no-workspaces state", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete" }));
    expect(await screen.findByText("No workspaces")).toBeInTheDocument();
    expect(api.rows).toEqual([]);
  });

  it("closes an item menu on an outside click", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
    await user.click(heading());
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("imports a workspace named after the file", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Import workspace" }));
    await user.upload(
      screen.getByTestId("import-input"),
      new File(["{}"], "Imported.json", { type: "application/json" }),
    );
    await waitFor(() => expect(heading()).toHaveTextContent("Imported"));
  });

  it("switches to Knowledge and My Files, and back to a workspace", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Knowledge" }));
    expect(screen.getByText("No knowledge yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "My Files" }));
    expect(screen.getByText("No files yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Starter" }));
    expect(heading()).toHaveTextContent("Starter");
  });

  it("shows the version, the API status and the theme switch in the account menu", async () => {
    const user = userEvent.setup();
    await renderLoaded();
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
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Logout" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows a loading state, then an error the user can dismiss", async () => {
    api.state.fail = 500;
    render(<App />);
    expect(screen.getByText("Loading workspaces…")).toBeInTheDocument();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load workspaces");
    await userEvent.setup().click(within(alert).getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows an unreachable API", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await waitFor(() => expect(screen.getByText("unreachable")).toBeInTheDocument());
  });
});

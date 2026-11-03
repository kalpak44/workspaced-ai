import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { FakeEventSource, installFakeApi } from "./fakeApi";

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

  it("asks for a name, suggesting the next free one, from either button", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    const dialog = screen.getByRole("dialog", { name: "New workspace" });
    const field = within(dialog).getByRole("textbox", { name: "Name" });
    expect(field).toHaveValue("New Workspace");
    expect(field).toHaveFocus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(within(screen.getByRole("main")).getByRole("button", { name: "New Workspace" }));
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("New Workspace 2");
    await user.keyboard("{Enter}");
    expect(await within(sidebar()).findByText("New Workspace 2")).toBeInTheDocument();
    expect(heading()).toHaveTextContent("New Workspace 2");
    expect(api.rows.map((w) => w.name)).toEqual(["Starter", "New Workspace", "New Workspace 2"]);
  });

  it("creates the workspace under the name typed", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    // Typing replaces the selected suggestion, the way it does in a browser.
    await user.keyboard("Research");
    await user.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(heading()).toHaveTextContent("Research"));
    expect(api.rows.at(-1)?.name).toBe("Research");
  });

  it("will not create a workspace without a name", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.clear(screen.getByRole("textbox", { name: "Name" }));
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("closes the dialog on Cancel, Escape or the backdrop without creating anything", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    const open = () => user.click(screen.getByRole("button", { name: "New workspace" }));

    await open();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await open();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await open();
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    expect(api.rows).toHaveLength(1);
  });

  it("shows a loader while creating, and cannot be closed until it finishes", async () => {
    let release = () => {};
    api.state.holdCreate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.keyboard("{Enter}");

    const create = screen.getByRole("button", { name: "Create" });
    expect(create).toHaveAttribute("aria-busy", "true");
    expect(create).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Name" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    release();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("keeps the dialog and what was typed when creation fails", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    api.state.fail = 503;
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not create the workspace");
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("New Workspace");
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
    expect(api.rows).toEqual([{ id: "starter", name: "Research", status: "READY" }]);

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

  it("shows the workspace deleting until its pod is gone", async () => {
    let release = () => {};
    api.state.holdDelete = new Promise<void>((resolve) => {
      release = resolve;
    });
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete" }));

    expect(within(sidebar()).getByRole("status", { name: "Deleting" })).toBeInTheDocument();
    expect(screen.getByText("Deleting workspace")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actions for Starter" })).toBeDisabled();
    // The pod disappearing mid-delete is expected, not a failure to show.
    FakeEventSource.emit("starter", "FAILED");
    expect(screen.getByText("Deleting workspace")).toBeInTheDocument();

    release();
    expect(await screen.findByText("No workspaces")).toBeInTheDocument();
  });

  it("puts the workspace back when its delete fails", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    api.state.fail = 500;
    await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not delete the workspace");
    expect(screen.getByText("Empty workspace")).toBeInTheDocument();
    expect(within(sidebar()).queryByRole("status")).not.toBeInTheDocument();
  });

  it("drops a workspace another tab deleted", async () => {
    await renderLoaded();
    FakeEventSource.emitDeleted("starter");
    expect(screen.getByText("No workspaces")).toBeInTheDocument();
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
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Imported");
    await user.click(screen.getByRole("button", { name: "Create" }));
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
    expect(within(screen.getByRole("main")).getByText("Loading workspaces…").parentElement?.querySelector(".animate-spin")).not.toBeNull();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load workspaces");
    await userEvent.setup().click(within(alert).getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows a new workspace initializing until its environment is ready", async () => {
    api.state.createStatus = "INITIALIZING";
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await user.keyboard("{Enter}");

    expect(await screen.findByText("Initializing workspace")).toBeInTheDocument();
    expect(within(sidebar()).getByRole("status", { name: "Initializing" })).toBeInTheDocument();

    FakeEventSource.emit("ws-1", "READY");
    expect(screen.getByText("Empty workspace")).toBeInTheDocument();
    expect(within(sidebar()).queryByRole("status")).not.toBeInTheDocument();
  });

  it("shows a workspace whose environment failed", async () => {
    await renderLoaded();
    FakeEventSource.emit("starter", "FAILED");
    expect(screen.getByText("Workspace unavailable")).toBeInTheDocument();
    expect(within(sidebar()).getByRole("status", { name: "Failed" })).toBeInTheDocument();
  });

  it("shows an unreachable API", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network")));
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await waitFor(() => expect(screen.getByText("unreachable")).toBeInTheDocument());
  });
});

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { FakeEventSource, installFakeApi, REJECTED_KEY } from "./fakeApi";

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
const sidebar = () => screen.getAllByRole("list")[0];
type User = ReturnType<typeof userEvent.setup>;

/** The wizard's steps after the name: pick OpenRouter, which reveals its key field, and type a key. */
async function chooseProvider(user: User, key = "sk-or-1") {
  await user.click(await screen.findByRole("radio", { name: /OpenRouter/ }));
  if (key) await user.type(screen.getByLabelText("OpenRouter API key"), key);
}

/** Opens the selected workspace's settings and waits for its secrets. */
async function openSettings(user: User) {
  await user.click(screen.getByRole("button", { name: "Workspace settings" }));
  await screen.findByText("OPENROUTER_API_KEY");
  return screen.getByRole("region", { name: "Secrets" });
}

describe("App", () => {
  it("opens on the chat of the first workspace from the API", async () => {
    await renderLoaded();
    expect(heading()).toHaveTextContent("Starter");
    expect(screen.getByRole("region", { name: "Chat" })).toBeInTheDocument();
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled();
  });

  it("asks for a name, suggesting the next free one, from either button", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    const dialog = screen.getByRole("dialog", { name: "New workspace" });
    const field = within(dialog).getByRole("textbox", { name: "Name" });
    expect(field).toHaveValue("New Workspace");
    expect(field).toHaveFocus();
    await chooseProvider(user);
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "New workspace" }));
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("New Workspace 2");
    await chooseProvider(user);
    await user.keyboard("{Enter}");
    expect(await within(sidebar()).findByText("New Workspace 2")).toBeInTheDocument();
    expect(heading()).toHaveTextContent("New Workspace 2");
    expect(api.rows.map((w) => w.name)).toEqual(["Starter", "New Workspace", "New Workspace 2"]);
  });

  it("creates the workspace under the name typed, with the provider and key chosen", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    // Typing replaces the selected suggestion, the way it does in a browser.
    await user.keyboard("Research");
    await chooseProvider(user, "  sk-or-1\t");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    await waitFor(() => expect(heading()).toHaveTextContent("Research"));
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
    expect(api.rows.at(-1)).toMatchObject({ name: "Research", modelProvider: "openrouter" });
    const [, init] = api.fetchFn.mock.calls.find(([, i]) => i?.method === "POST")!;
    expect(JSON.parse(String(init?.body))).toEqual({
      name: "Research",
      modelProvider: "openrouter",
      apiKey: "sk-or-1",
    });
  });

  it("asks for the key only once a provider is chosen, and needs all three", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    const next = screen.getByRole("button", { name: "Continue" });
    expect(screen.queryByLabelText("OpenRouter API key")).not.toBeInTheDocument();
    expect(next).toBeDisabled();

    await chooseProvider(user, "");
    expect(screen.getByRole("radio", { name: /OpenRouter/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /openrouter\.ai/ })).toBeInTheDocument();
    expect(screen.getByLabelText("OpenRouter API key")).toHaveFocus();
    expect(screen.getByLabelText("OpenRouter API key")).toHaveAccessibleDescription(
      /Checked with OpenRouter before the workspace is created/,
    );
    expect(screen.getByLabelText("OpenRouter API key")).toHaveAttribute("type", "password");
    expect(next).toBeDisabled();
    await user.keyboard("sk-or-1");
    expect(next).toBeEnabled();
    await user.clear(screen.getByRole("textbox", { name: "Name" }));
    expect(next).toBeDisabled();
  });

  it("keeps the dialog open and points at the key when the provider rejects it", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await chooseProvider(user, REJECTED_KEY);
    await user.keyboard("{Enter}");

    const key = await screen.findByLabelText("OpenRouter API key");
    await waitFor(() => expect(key).toHaveAccessibleDescription("OpenRouter did not accept this key"));
    expect(key).toHaveAttribute("aria-invalid", "true");
    expect(key).toHaveFocus();
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(api.rows).toHaveLength(1);

    await user.clear(key);
    await user.type(key, "sk-or-2{Enter}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
  });

  it("says so when the providers cannot be loaded", async () => {
    api.state.fail = 500;
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    const providers = screen.getByRole("group", { name: "Model provider" });
    expect(within(providers).getByText(/Could not load providers/)).toBeInTheDocument();
    expect(within(providers).queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
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
    await chooseProvider(user);
    await user.keyboard("{Enter}");

    const create = screen.getByRole("button", { name: "Continue" });
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
    await user.click(screen.getByRole("button", { name: "New workspace" }));
    await chooseProvider(user);
    api.state.fail = 503;
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not create the workspace");
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("New Workspace");
    expect(screen.getByLabelText("OpenRouter API key")).toHaveValue("sk-or-1");
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
    expect(api.rows).toEqual([
      { id: "starter", name: "Research", modelProvider: "openrouter", status: "READY" },
    ]);

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
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
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
    await chooseProvider(user);
    await user.click(screen.getByRole("button", { name: "Continue" }));
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
    await chooseProvider(user);
    await user.keyboard("{Enter}");

    expect(await screen.findByText("Initializing workspace")).toBeInTheDocument();
    expect(within(sidebar()).getByRole("status", { name: "Initializing" })).toBeInTheDocument();

    FakeEventSource.emit("ws-1", "READY");
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
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

  it("lists the provider key in settings as managed, with no way to remove it", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    const secrets = await openSettings(user);
    expect(heading()).toHaveTextContent("Starter · Settings");
    expect(within(secrets).getByText("Model provider")).toBeInTheDocument();
    expect(within(secrets).queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Back to chat" }));
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
  });

  it("adds a secret by name and value, and never shows the value again", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    const secrets = await openSettings(user);
    const add = within(secrets).getByRole("button", { name: "Add" });
    expect(add).toBeDisabled();
    await user.type(within(secrets).getByRole("textbox", { name: "Name" }), "GITHUB_TOKEN");
    await user.type(within(secrets).getByLabelText("Value"), "ghp_secret");
    await user.click(add);

    expect(await within(secrets).findByText("GITHUB_TOKEN")).toBeInTheDocument();
    expect(within(secrets).getByRole("textbox", { name: "Name" })).toHaveValue("");
    expect(within(secrets).getByLabelText("Value")).toHaveValue("");
    expect(screen.queryByText("ghp_secret")).not.toBeInTheDocument();
    expect(api.sent).toEqual([{ name: "GITHUB_TOKEN", value: "ghp_secret" }]);
  });

  it("refuses a name that is not an environment variable, or one already set", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    const secrets = await openSettings(user);
    const name = within(secrets).getByRole("textbox", { name: "Name" });
    await user.type(name, "1-TOKEN");
    await user.type(within(secrets).getByLabelText("Value"), "x");
    await user.click(within(secrets).getByRole("button", { name: "Add" }));
    expect(name).toHaveAccessibleDescription(
      "Use letters, digits and underscores, not starting with a digit",
    );
    expect(api.sent).toEqual([]);

    await user.clear(name);
    await user.type(name, "OPENROUTER_API_KEY");
    await user.click(within(secrets).getByRole("button", { name: "Add" }));
    await waitFor(() =>
      expect(name).toHaveAccessibleDescription("OPENROUTER_API_KEY is already set for this workspace"),
    );
    expect(name).toHaveValue("OPENROUTER_API_KEY");

    api.state.fail = 500;
    await user.click(within(secrets).getByRole("button", { name: "Add" }));
    await waitFor(() => expect(name).toHaveAccessibleDescription("Could not add the secret"));
  });

  it("removes a secret, showing it removing until the API answers", async () => {
    api.secrets.set("starter", ["GITHUB_TOKEN"]);
    let release = () => {};
    api.state.holdSecretRemove = new Promise<void>((resolve) => {
      release = resolve;
    });
    const user = userEvent.setup();
    await renderLoaded();
    const secrets = await openSettings(user);
    await user.click(within(secrets).getByRole("button", { name: "Remove GITHUB_TOKEN" }));
    expect(within(secrets).getByRole("status", { name: "Removing GITHUB_TOKEN" })).toBeInTheDocument();
    release();
    await waitFor(() => expect(within(secrets).queryByText("GITHUB_TOKEN")).not.toBeInTheDocument());
    expect(api.secrets.get("starter")).toEqual([]);
  });

  it("reports secrets that cannot be loaded or removed", async () => {
    api.secrets.set("starter", ["GITHUB_TOKEN"]);
    const user = userEvent.setup();
    await renderLoaded();
    const secrets = await openSettings(user);
    api.state.fail = 500;
    await user.click(within(secrets).getByRole("button", { name: "Remove GITHUB_TOKEN" }));
    expect(await within(secrets).findByRole("alert")).toHaveTextContent("Could not remove GITHUB_TOKEN");
    expect(within(secrets).getByText("GITHUB_TOKEN")).toBeInTheDocument();
    await user.click(within(secrets).getByRole("button", { name: "Dismiss" }));
    expect(within(secrets).queryByRole("alert")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Back to chat" }));
    await user.click(screen.getByRole("button", { name: "Workspace settings" }));
    expect(await screen.findByText("Could not load the secrets")).toBeInTheDocument();
  });

  it("closes settings when another workspace is selected", async () => {
    api.rows.push({ id: "other", name: "Other", modelProvider: "openrouter", status: "READY" });
    const user = userEvent.setup();
    await renderLoaded();
    await openSettings(user);
    await user.click(screen.getByRole("button", { name: "Other" }));
    expect(heading()).toHaveTextContent("Other");
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Starter" }));
    expect(screen.getByText("Start a conversation")).toBeInTheDocument();
  });

  it("opens settings for a workspace that is not ready", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    FakeEventSource.emit("starter", "FAILED");
    await openSettings(user);
    expect(heading()).toHaveTextContent("Starter · Settings");
  });
});

import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
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
  // The sidebar's entry; a breadcrumb may name the workspace too.
  await screen.findAllByRole("button", { name: "Starter" });
}

const heading = () => screen.getByRole("heading", { level: 1 });
const sidebar = () => screen.getAllByRole("list")[0];
type User = ReturnType<typeof userEvent.setup>;

/** The wizard's steps after the name: pick OpenRouter, which reveals its key field, and type a key. */
async function chooseProvider(user: User, key = "sk-or-1") {
  await user.click(await screen.findByRole("radio", { name: /OpenRouter/ }));
  if (key) await user.type(screen.getByLabelText("OpenRouter API key"), key);
}

const path = () => globalThis.location.pathname;
const sessionsHere = () => screen.findByRole("heading", { name: "Start a session" });

/** Opens a page by URL before the app renders, as a pasted link or a reload would. */
function visit(url: string) {
  globalThis.history.replaceState(null, "", url);
}

/** Opens the selected workspace's settings and waits for its secrets. */
async function openSettings(user: User) {
  await user.click(screen.getByRole("button", { name: "Workspace settings" }));
  await screen.findByText("OPENROUTER_API_KEY");
  return screen.getByRole("region", { name: "Secrets" });
}

describe("App", () => {
  it("opens the first workspace's sessions at its own address", async () => {
    await renderLoaded();
    await waitFor(() => expect(path()).toBe("/workspaces/starter"));
    expect(heading()).toHaveTextContent("Starter");
    await sessionsHere();
    expect(screen.getByRole("region", { name: "Sessions" })).toBeInTheDocument();
    // The page exists to start work, so the task box is ready to type into.
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveFocus();
    expect(document.title).toBe("Starter — workspaced-ai");
  });

  it("asks for a name, suggesting the next free one", async () => {
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
    expect(path()).toBe("/workspaces/ws-1");
    await sessionsHere();
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
    await sessionsHere();
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
      { id: "starter", name: "Research", modelProvider: "openrouter", model: null, status: "READY" },
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
    expect(await screen.findByText("Could not delete the workspace")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Sessions" })).toBeInTheDocument();
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

    expect(await screen.findByText("Starting the workspace")).toBeInTheDocument();
    expect(within(sidebar()).getByRole("status", { name: "Initializing" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New session" })).toBeDisabled();

    // The page opens on its own once the workspace is ready.
    FakeEventSource.emit("ws-1", "READY");
    await sessionsHere();
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
    expect(heading()).toHaveTextContent("Settings");
    expect(path()).toBe("/workspaces/starter/settings");
    expect(document.title).toBe("Settings · Starter — workspaced-ai");
    expect(within(secrets).getByText("Model provider")).toBeInTheDocument();
    expect(within(secrets).queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();

    await user.click(within(screen.getByRole("navigation", { name: "Breadcrumb" })).getByRole("button", { name: "Starter" }));
    expect(path()).toBe("/workspaces/starter");
    await sessionsHere();
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
  });

  it("reports secrets that cannot be loaded", async () => {
    visit("/workspaces/starter/settings");
    await renderLoaded();
    api.state.fail = 500;
    // Loaded before the failure, so reopen the page to load again.
    act(() => {
      globalThis.history.pushState(null, "", "/workspaces/starter");
      globalThis.dispatchEvent(new PopStateEvent("popstate"));
    });
    await userEvent.setup().click(screen.getByRole("button", { name: "Workspace settings" }));
    expect(await screen.findByText("Could not load the secrets")).toBeInTheDocument();
  });

  it("leaves settings when another workspace is selected", async () => {
    api.rows.push({ id: "other", name: "Other", modelProvider: "openrouter", model: null, status: "READY" });
    const user = userEvent.setup();
    await renderLoaded();
    await openSettings(user);
    await user.click(screen.getByRole("button", { name: "Other" }));
    expect(heading()).toHaveTextContent("Other");
    expect(path()).toBe("/workspaces/other");
    await sessionsHere();
  });

  it("opens settings for a workspace that is not ready", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    FakeEventSource.emit("starter", "FAILED");
    expect(screen.getByText("Workspace unavailable")).toBeInTheDocument();
    await openSettings(user);
    expect(heading()).toHaveTextContent("Settings");
  });

  it("starts a session from a typed task and streams the reply into its own page", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await sessionsHere();
    await user.type(screen.getByRole("textbox", { name: "Message" }), "List the files{Enter}");

    await waitFor(() => expect(path()).toBe("/workspaces/starter/sessions/s-1"));
    // Sent by the session page once it is listening, so the reply streams from its first token.
    await waitFor(() => expect(api.prompts).toEqual([{ sessionId: "s-1", text: "List the files" }]));
    expect(FakeEventSource.latest("/sessions/s-1/events")).toBeDefined();
    expect(heading()).toHaveTextContent("New session");
    expect(screen.getByText("Working…")).toBeInTheDocument();

    FakeEventSource.emitSession("status", { busy: true });
    FakeEventSource.emitSession("message", { id: "m1", role: "user" });
    FakeEventSource.emitSession("part", {
      messageId: "m1",
      part: { id: "p1", type: "text", text: "List the files", tool: null, status: null },
    });
    FakeEventSource.emitSession("message", { id: "m2", role: "assistant" });
    FakeEventSource.emitSession("part", {
      messageId: "m2",
      part: { id: "t1", type: "tool", text: "List files", tool: "bash", status: "running" },
    });
    FakeEventSource.emitSession("delta", { messageId: "m2", partId: "p2", delta: "There are " });
    FakeEventSource.emitSession("delta", { messageId: "m2", partId: "p2", delta: "**none**." });

    expect(screen.getByRole("listitem", { name: "You" })).toHaveTextContent("List the files");
    const agent = screen.getByRole("listitem", { name: "Agent" });
    expect(agent).toHaveTextContent("There are none.");
    expect(within(agent).getByText("none").tagName).toBe("STRONG");
    expect(within(agent).getByText("bash")).toBeInTheDocument();
    expect(within(agent).getByRole("status", { name: "Running" })).toBeInTheDocument();

    // While the agent works, the send button stops it instead.
    await user.click(screen.getByRole("button", { name: "Stop" }));
    await waitFor(() => expect(api.aborted).toEqual(["s-1"]));

    // The model the workspace is connected to names the session after the first exchange.
    FakeEventSource.emitSession("title", { title: "Listing files" });
    expect(heading()).toHaveTextContent("Listing files");
    expect(document.title).toBe("Listing files · Starter — workspaced-ai");

    api.messages.set("s-1", [
      { id: "m1", role: "user", parts: [{ id: "p1", type: "text", text: "List the files", tool: null, status: null }] },
      {
        id: "m2",
        role: "assistant",
        parts: [
          { id: "t1", type: "tool", text: "List files", tool: "bash", status: "completed" },
          { id: "p2", type: "text", text: "There are **none**.", tool: null, status: null },
        ],
      },
    ]);
    FakeEventSource.emitSession("status", { busy: false });
    // Idle reconciles with the history, which holds the finished tool call.
    expect(await within(agent).findByRole("img", { name: "Done" })).toBeInTheDocument();
    expect(screen.queryByText("Working…")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeInTheDocument();
  });

  it("lists sessions newest first and opens one with its history", async () => {
    const twoDaysAgo = new Date(Date.now() - 2 * 24 * 3600 * 1000).toISOString();
    api.sessions.set("starter", [
      { id: "old", title: "Old chat", path: "/home/workspace/sessions/old", createdAt: twoDaysAgo },
      { id: "new", title: null, path: "/home/workspace/sessions/new", createdAt: new Date().toISOString() },
    ]);
    api.messages.set("old", [
      {
        id: "m1",
        role: "assistant",
        parts: [
          { id: "r", type: "reasoning", text: "Thinking it over", tool: null, status: null },
          { id: "x", type: "tool", text: null, tool: "edit", status: "error" },
          { id: "e", type: "text", text: "", tool: null, status: null },
          { id: "t", type: "text", text: "Earlier answer <b>raw</b>\n\n- one\n- two", tool: null, status: null },
        ],
      },
    ]);
    const user = userEvent.setup();
    await renderLoaded();
    const list = await screen.findByRole("region", { name: "Sessions" });
    await within(list).findByText("Old chat");
    expect(within(list).getAllByRole("listitem").map((card) => card.textContent)).toEqual([
      "New sessionjust now",
      "Old chat2 days ago",
    ]);
    expect(within(list).getByText("2 days ago")).toBeInTheDocument();

    await user.click(within(list).getByText("Old chat"));
    expect(path()).toBe("/workspaces/starter/sessions/old");
    expect(heading()).toHaveTextContent("Old chat");
    const agent = await screen.findByRole("listitem", { name: "Agent" });
    expect(agent).toHaveTextContent("Earlier answer");
    // Model output is markdown, never markup: raw HTML is shown, not rendered.
    expect(agent.querySelector("b")).toBeNull();
    expect(agent).toHaveTextContent("<b>raw</b>");
    expect(within(agent).getAllByRole("listitem").map((li) => li.textContent)).toEqual(["one", "two"]);
    expect(within(agent).getByText("Thinking")).toBeInTheDocument();
    expect(within(agent).getByRole("img", { name: "Failed" })).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "Message" }), "More{Enter}");
    await waitFor(() => expect(api.prompts).toEqual([{ sessionId: "old", text: "More" }]));

    // Back returns to the list, as the browser's own button does.
    act(() => {
      globalThis.history.back();
    });
    await waitFor(() => expect(path()).toBe("/workspaces/starter"));
    await sessionsHere();
  });

  it("opens an empty session from the button, and deletes one from its page", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await sessionsHere();
    await user.click(screen.getByRole("button", { name: "New session" }));
    await waitFor(() => expect(path()).toBe("/workspaces/starter/sessions/s-1"));
    expect(await screen.findByText("What should the agent do?")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "Delete session" }));
    expect(path()).toBe("/workspaces/starter");
    await waitFor(() => expect(api.sessions.get("starter")).toEqual([]));
    await sessionsHere();
  });

  it("deletes a session from the list", async () => {
    api.sessions.set("starter", [
      { id: "a", title: "Keep", path: "/p/a", createdAt: new Date().toISOString() },
    ]);
    const user = userEvent.setup();
    await renderLoaded();
    const list = await screen.findByRole("region", { name: "Sessions" });
    await user.click(await within(list).findByRole("button", { name: "Delete Keep" }));
    await waitFor(() => expect(within(list).queryByText("Keep")).not.toBeInTheDocument());
    expect(within(list).getByText(/No sessions yet/)).toBeInTheDocument();
  });

  it("reports a harness that is not answering, and keeps what was typed", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    await renderLoaded();
    expect(await screen.findByText("Could not load the sessions")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Dismiss" }));

    const message = screen.getByRole("textbox", { name: "Message" });
    await user.type(message, "Hello{Enter}");
    expect(await screen.findByText("Could not start a session. Is the workspace ready?")).toBeInTheDocument();
    expect(message).toHaveValue("Hello");
    expect(path()).toBe("/workspaces/starter");
  });

  it("opens a session from a link, and reports what goes wrong in it", async () => {
    api.sessions.set("starter", [{ id: "a", title: "Chat", path: "/p/a", createdAt: new Date().toISOString() }]);
    visit("/workspaces/starter/sessions/a");
    const user = userEvent.setup();
    await renderLoaded();
    expect(await screen.findByRole("heading", { level: 1, name: "Chat" })).toBeInTheDocument();
    await waitFor(() => expect(() => FakeEventSource.latest("/sessions/a/events")).not.toThrow());
    FakeEventSource.emitSession("error", { message: "User not found." });
    expect(screen.getByText("User not found.")).toBeInTheDocument();

    api.state.harnessDown = true;
    await user.type(screen.getByRole("textbox", { name: "Message" }), "Hi{Enter}");
    expect(await screen.findByText("Could not send the message")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeInTheDocument();
  });

  it("sends a stale session link back to its workspace, and an unknown workspace home", async () => {
    visit("/workspaces/starter/sessions/gone");
    await renderLoaded();
    await waitFor(() => expect(path()).toBe("/workspaces/starter"));
    cleanup();

    visit("/workspaces/nowhere");
    await renderLoaded();
    await waitFor(() => expect(path()).toBe("/workspaces/starter"));
  });

  it("does not ask a workspace that is still starting for its sessions", async () => {
    api.rows[0] = { ...api.rows[0], status: "INITIALIZING" };
    render(<App />);
    expect(await screen.findByText("Starting the workspace")).toBeInTheDocument();
    expect(api.fetchFn.mock.calls.map(([url]) => String(url))).not.toContainEqual(
      expect.stringContaining("/sessions"),
    );
  });

  it("chooses the agent's model from a searchable list, and goes back to the default", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await openSettings(user);
    const model = await screen.findByRole("radiogroup", { name: "Model" });
    expect(within(model).getByRole("radio", { name: /Default — Claude Sonnet 5.5/ })).toBeChecked();
    expect(within(model).getByRole("radio", { name: /Claude Haiku 5.5/ })).toHaveTextContent(
      "200K context · $1 / $5",
    );
    expect(within(model).getByRole("radio", { name: /GPT Free/ })).toHaveTextContent("free");

    await user.type(screen.getByRole("textbox", { name: "Search models" }), "haiku");
    expect(within(model).getAllByRole("radio")).toHaveLength(1);
    await user.click(within(model).getByRole("radio", { name: /Claude Haiku 5.5/ }));
    await waitFor(() =>
      expect(within(model).getByRole("radio", { name: /Claude Haiku 5.5/ })).toBeChecked(),
    );
    expect(api.rows[0].model).toBe("anthropic/claude-haiku-5.5");
    expect(screen.getByRole("region", { name: "Model" })).toHaveTextContent("Now: Claude Haiku 5.5");

    await user.clear(screen.getByRole("textbox", { name: "Search models" }));
    await user.type(screen.getByRole("textbox", { name: "Search models" }), "nothing like it");
    expect(within(model).getByText(/No model matches/)).toBeInTheDocument();
    await user.clear(screen.getByRole("textbox", { name: "Search models" }));
    await user.click(within(model).getByRole("radio", { name: /Default/ }));
    await waitFor(() => expect(api.rows[0].model).toBeNull());
  });

  it("explains the model list waits for a running workspace, and reports one it cannot load", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    FakeEventSource.emit("starter", "INITIALIZING");
    await openSettings(user);
    expect(screen.getByText(/shows once the workspace is running/)).toBeInTheDocument();
    cleanup();

    api.state.harnessDown = true;
    visit("/workspaces/starter/settings");
    await renderLoaded();
    expect(await screen.findByText("Could not load the models")).toBeInTheDocument();
  });

  it("adds an MCP server with a header, shows it connected, and removes it", async () => {
    const user = userEvent.setup();
    await renderLoaded();
    await openSettings(user);
    const panel = screen.getByRole("region", { name: "MCP servers" });
    const form = within(panel).getByRole("form", { name: "Add an MCP server" });
    await user.type(within(form).getByRole("textbox", { name: "Name" }), "github");
    await user.type(within(form).getByLabelText("URL"), "https://mcp.test/github");
    await user.click(within(form).getByRole("button", { name: "Add header" }));
    await user.click(within(form).getByRole("button", { name: "Add header" }));
    await user.click(within(form).getByRole("button", { name: "Remove header 2" }));
    await user.type(within(form).getByLabelText("Header 1 name"), "Authorization");
    await user.type(within(form).getByLabelText("Header 1 value"), "Bearer {{env:GITHUB_TOKEN}");
    await user.click(within(form).getByRole("button", { name: "Add server" }));

    const row = (await within(panel).findByText("github")).closest("li")!;
    expect(row).toHaveTextContent("https://mcp.test/github · sends Authorization");
    expect(row).toHaveTextContent("Connected");
    expect(within(form).getByRole("textbox", { name: "Name" })).toHaveValue("");
    expect(within(form).queryByLabelText("Header 1 name")).not.toBeInTheDocument();

    await user.click(within(panel).getByRole("button", { name: "Remove github" }));
    await waitFor(() => expect(within(panel).queryByText("github")).not.toBeInTheDocument());
    expect(api.mcp.get("starter")).toEqual([]);
  });

  it("checks an MCP server before sending it, and says why the API refused one", async () => {
    api.mcp.set("starter", [{ name: "github", url: "https://x.test", headers: [], status: "failed" }]);
    const user = userEvent.setup();
    await renderLoaded();
    await openSettings(user);
    const panel = screen.getByRole("region", { name: "MCP servers" });
    expect(await within(panel).findByText("Failed")).toBeInTheDocument();
    const name = within(panel).getByRole("textbox", { name: "Name" });
    const url = within(panel).getByLabelText("URL");

    await user.type(name, "Bad Name");
    await user.type(url, "https://x.test");
    await user.click(within(panel).getByRole("button", { name: "Add server" }));
    expect(name).toHaveAccessibleDescription("Use lowercase letters, digits, - and _");

    await user.clear(name);
    await user.type(name, "x");
    await user.clear(url);
    await user.type(url, "ftp://x.test");
    await user.click(within(panel).getByRole("button", { name: "Add server" }));
    expect(name).toHaveAccessibleDescription("The URL must start with http:// or https://");

    await user.clear(name);
    await user.type(name, "github");
    await user.clear(url);
    await user.type(url, "https://x.test");
    await user.click(within(panel).getByRole("button", { name: "Add server" }));
    await waitFor(() => expect(name).toHaveAccessibleDescription("The workspace already has a server of that name"));

    await user.clear(name);
    await user.type(name, "other");
    await user.clear(url);
    await user.type(url, "https://invalid.test");
    await user.click(within(panel).getByRole("button", { name: "Add server" }));
    await waitFor(() => expect(name).toHaveAccessibleDescription("Check the URL and header names"));

    api.state.fail = 500;
    await user.click(within(panel).getByRole("button", { name: "Add server" }));
    await waitFor(() => expect(name).toHaveAccessibleDescription("Could not add the server"));
    await user.click(within(panel).getByRole("button", { name: "Remove github" }));
    expect(await within(panel).findByText("Could not remove github")).toBeInTheDocument();
  });
});

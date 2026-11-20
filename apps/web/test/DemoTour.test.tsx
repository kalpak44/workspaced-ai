import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { Recipe } from "../src/api/recipesApi";
import type { Workspace } from "../src/api/workspacesApi";
import { DemoTour } from "../src/components/organisms/DemoTour";
import * as tour from "../src/lib/tour";
import { FakeEventSource, installFakeApi } from "./fakeApi";

vi.mock("../src/hooks/useOpenRouterConnect", () => ({
  useOpenRouterConnect: (onKey: (key: string) => void) => ({
    status: "idle",
    connect: async (label: () => Promise<string | null>) => { if (await label()) onKey("private-openrouter-key"); },
  }),
}));
const S = tour.TOUR_STEP;
const actualType = tour.typeInto;
const workspace: Workspace = { id: "trial", name: "My blog", modelProvider: "openrouter", model: null, timeZone: "Europe/Sofia", status: "READY", restartRequired: false, demo: true, expiresAt: new Date(Date.now() + 14400000).toISOString() };
let api: ReturnType<typeof installFakeApi>;
let user: ReturnType<typeof userEvent.setup>;
const guide = () => screen.getByRole("region", { name: "Guided tour controls" });
async function next(label = "Next") {
  const button = within(guide()).getByRole("button", { name: label });
  await waitFor(() => expect(button, screen.getByRole("region", { name: "Guided tour" }).textContent ?? "").toBeEnabled(), { timeout: 5000 });
  await user.click(button);
  await waitFor(() => { if (button.isConnected) expect(button).not.toHaveAttribute("aria-busy", "true"); }, { timeout: 5000 });
}
async function title(name: string) { return screen.findByRole("heading", { name }); }
function open(step: number, path = "/workspaces/trial") {
  tour.setTourProgress({ step, workspaceId: workspace.id });
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}
function clickSelector(selector: string) {
  const node = document.querySelector<HTMLElement>(selector);
  expect(node, selector).not.toBeNull();
  fireEvent.click(node!);
}
async function answer(sessionId: string, text: string, canContinue = true) {
  const prompts = api.prompts.filter((prompt) => prompt.sessionId === sessionId);
  const history = api.messages.get(sessionId) ?? [];
  api.messages.set(sessionId, prompts.flatMap((prompt, index) => [
    { id: `u-${index}`, role: "user" as const, parts: [{ id: `up-${index}`, type: "text" as const, text: prompt.text, tool: null, status: null }] },
    ...(index === prompts.length - 1
      ? [{ id: `a-${index + 1}`, role: "assistant" as const, parts: [{ id: "reply", type: "text" as const, text, tool: null, status: null }] }]
      : history.filter((message) => message.id === `a-${index + 1}`)),
  ]));
  FakeEventSource.emitSession("status", { busy: false });
  await waitFor(() => {
    const button = within(guide()).getByRole("button", { name: "Next" });
    if (canContinue) expect(button, text + " :: " + screen.getByRole("region", { name: "Guided tour" }).textContent).toBeEnabled(); else expect(button).toBeDisabled();
  });
}

beforeEach(() => {
  api = installFakeApi([workspace]);
  user = userEvent.setup();
  vi.spyOn(tour, "typeInto").mockImplementation((field, text, _delay, signal) => actualType(field, text, 0, signal));
});

describe("full-screen guided WordPress workflow", () => {
  it("opens the real naming form automatically and keeps a user-written name", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true };
    globalThis.history.replaceState(null, "", "/?welcome");
    render(<App />);
    await title("Your own workspace, step by step");
    expect(document.documentElement.dataset.guidedTour).toBe("true");
    expect(screen.queryByRole("button", { name: "Start the tour" })).not.toBeInTheDocument();
    await next();
    await title("Give your workspace a name");
    const name = await screen.findByRole("textbox", { name: "Name" });
    await user.type(name, "My own project");
    await next();
    await title("Connect your own OpenRouter account");
    expect(localStorage.getItem("workspaced.tour.v3")).toContain("My own project");
    expect(api.launched).toHaveLength(0);
  });

  it("demonstrates a default name in the real form without submitting until the next click", async () => {
    open(S.WELCOME, "/");
    await title("Your own workspace, step by step");
    await next();
    await title("Give your workspace a name");
    await next();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(tour.TOUR_WORKSPACE_NAME);
    expect(screen.getByRole("dialog", { name: "New workspace" })).toBeInTheDocument();
    await next();
    await title("Connect your own OpenRouter account");
  });

  it("keeps OpenRouter and one affordable machine custom, requiring deletion acknowledgement", async () => {
    tour.setTourProgress({ step: S.OPENROUTER, name: "My chosen name" });
    const onDraft = vi.fn().mockResolvedValue({ id: "trial" });
    const onLaunch = vi.fn().mockResolvedValue("created");
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={onDraft} onLaunch={onLaunch} />);
    await next();
    await title("Choose the machine for this trial");
    await screen.findByRole("radio", { name: "cx23" });
    expect(screen.getByRole("radio", { name: "cx23" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "cx33" })).toBeDisabled();
    expect(onDraft).toHaveBeenCalledWith(expect.objectContaining({ name: "My chosen name", demo: true }));
    const create = within(guide()).getByRole("button", { name: "Create free workspace" });
    expect(create).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /permanently deleted after four hours/ }));
    await next("Create free workspace");
    expect(onLaunch).toHaveBeenCalledWith("trial", "private-openrouter-key");
    expect(localStorage.getItem("workspaced.tour.v3")).not.toContain("private-openrouter-key");
  });

  it("uses the real Store, Apply, secrets, firewall, task and instruction forms", async () => {
    api.recipes.rows.push({ id: tour.TOUR_RECIPE_ID, title: "WordPress blog", summary: "A repeatable HTTPS blog", category: "websites", tags: ["wordpress"], visibility: "PUBLIC", author: { id: "pavel", firstName: "Pavel", lastName: "Usanli" }, createdAt: "2026-11-01T10:00:00Z", publishedAt: "2026-11-01T10:00:00Z", rating: { average: 0, count: 0 }, myRating: null, mine: false, content: "Check secrets, prepare Compose, ask for ports, publish with HTTPS, save blog.md." } satisfies Recipe);
    open(S.STORE);
    await title("Open the real recipe Store");
    await user.click(screen.getByRole("link", { name: "Store" }));
    await title("Recipes can belong just to you");
    await user.click(await screen.findByRole("tab", { name: "Private" }));
    await title("You can write or import your own");
    expect(screen.getByRole("button", { name: "New recipe" })).toBeInTheDocument();
    await next();
    await next();
    await title("Find WordPress blog");
    await screen.findByText("A repeatable HTTPS blog");
    clickSelector(`[data-recipe="${tour.TOUR_RECIPE_ID}"]`);
    await title("Read what the recipe will do");
    await next();
    await user.click(await screen.findByRole("button", { name: "Apply" }));
    await title("Apply it in your trial workspace");
    const picker = screen.getByRole("dialog", { name: "Apply WordPress blog" });
    await user.click(within(picker).getByRole("button", { name: "Apply" }));
    await title("Watch the recipe start in chat");
    await waitFor(() => expect(api.prompts).toHaveLength(1));
    expect(api.sent).toHaveLength(0);
    expect(api.prompts[0].text).toContain("Check secrets");
    const sessionId = api.prompts[0].sessionId;
    await answer(sessionId, "I am checking the workspace before starting.", false);
    expect(screen.queryByRole("heading", { name: "Find Secrets" })).not.toBeInTheDocument();
    await answer(sessionId, "Add WORDPRESS_ADMIN_USER, WORDPRESS_ADMIN_PASSWORD and WORDPRESS_DB_PASSWORD in Settings → Secrets, then restart.");
    await next();
    await next();
    await title("Find Secrets");
    await next();
    await title("Add the admin username as a secret");
    await next();
    expect(within(screen.getByRole("region", { name: "Secrets" })).getByLabelText("Name")).toHaveValue("WORDPRESS_ADMIN_USER");
    const secretName = within(screen.getByRole("region", { name: "Secrets" })).getByLabelText("Name");
    expect(secretName).toHaveAttribute("readonly");
    await user.type(secretName, "WRONG_KEY");
    expect(secretName).toHaveValue("WORDPRESS_ADMIN_USER");
    expect(screen.getByLabelText("Value", { exact: true })).toHaveValue("blog-admin");
    await user.click(screen.getByRole("button", { name: "Add" }));
    await title("Add your own admin password");
    await next();
    await user.type(screen.getByLabelText("Value", { exact: true }), "my-private-password");
    await user.click(screen.getByRole("button", { name: "Add" }));
    await title("Use a separate database password");
    await next();
    await user.click(screen.getByRole("button", { name: "Add" }));
    await title("Apply the secrets with a restart");
    expect(localStorage.getItem("workspaced.tour.v3")).not.toContain("my-private-password");
    expect(api.sent.filter((secret) => secret.name.startsWith("WORDPRESS_"))).toHaveLength(3);
    await next();
    await title("Confirm the restart");
    await user.click(within(screen.getByRole("dialog", { name: "Restart My blog?" })).getByRole("button", { name: "Restart workspace" }));
    await title("Wait for the workspace to return");
    expect(screen.getByRole("status", { name: "Workspace restarting" })).toBeInTheDocument();
    expect(within(guide()).getByRole("button", { name: "Next" })).toBeDisabled();
    await waitFor(() => expect(api.restarted).toEqual(["trial"]));
    FakeEventSource.emit("trial", "READY");
    await waitFor(() => expect(screen.queryByRole("status", { name: "Workspace restarting" })).not.toBeInTheDocument());
    await next();
    await title("Return through Sessions");
    await next();
    await title("Reopen the recipe conversation");
    await screen.findByText("New session");
    await next();
    await title("Tell the agent the secrets are ready");
    await next();
    await next();
    await title("Watch the installation");
    await answer(sessionId, "The private stack is ready. Open TCP ports 80 and 443 in Settings → Firewall.");
    await next(); await next(); await next();
    await title("Open TCP port 80 for certificate checks");
    await next();
    expect(screen.getByLabelText("Port", { exact: true })).toHaveValue("80");
    await user.click(screen.getByRole("button", { name: "Open port" }));
    await title("Open TCP port 443 for HTTPS");
    await next(); await next();
    await title("See what is now public");
    expect(screen.getByRole("region", { name: "Guided tour" })).toHaveTextContent("anyone on the internet");
    expect(api.firewalls.get("trial")?.rules.map((rule) => rule.port)).toEqual(["80", "443"]);
    await next(); await next(); await next();
    await title("Let the recipe finish publishing");
    await next(); await next();
    await title("Watch HTTPS and the first article appear");
    api.instructions.set("trial", new Map([["blog", "# Blog\nUse docker compose run --rm cli post create to publish articles."]]));
    await answer(sessionId, "The blog is verified at https://203.0.113.10. The blog instruction is saved.");
    await next();
    await next("My blog works");
    await next(); await next();
    await title("Create a news task");
    await user.click(await screen.findByRole("button", { name: "New task" }));
    await title("Name the task");
    await next(); await next();
    await title("Write what should run, and when");
    await next();
    expect(screen.getByLabelText("Cron expression")).toHaveValue("*/5 * * * *");
    const taskPrompt = screen.getByRole("textbox", { name: "Task prompt Markdown" });
    await user.type(taskPrompt, " Use British spelling.");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await title("Your automation is saved");
    expect(api.tasks.get("trial")?.get(tour.TOUR_TASK.name)?.prompt).toContain("Use British spelling.");
    await next(); await next();
    await title("The recipe left a CLI publishing guide");
    await screen.findByText(/post create to publish articles/);
    await next();
    await title("Add a rule of your own");
    await user.click(screen.getByRole("button", { name: "New instructions" }));
    await title("Name your instruction");
    await next(); await next();
    await title("Choose how the agent should write");
    await next(); await next();
    await title("Your writing rule is now shared");
    expect(api.instructions.get("trial")?.get(tour.TOUR_STYLE_NAME)).toBe(tour.TOUR_STYLE);
    await next(); await next(); await next();
    await title("Your turn: request a new design");
    const beforeDesign = api.prompts.length;
    await next();
    await title("Make it yours, then Send");
    expect(api.prompts).toHaveLength(beforeDesign);
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(tour.TOUR_DESIGN_PROMPT);
    await user.click(screen.getByRole("button", { name: "Send" }));
    await next("Finish tutorial");
    await waitFor(() => expect(screen.queryByRole("region", { name: "Guided tour" })).not.toBeInTheDocument());
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(true);
    expect(api.mail.sent).toHaveLength(0);
    const completion = await screen.findByRole("dialog", { name: "Tutorial completed" });
    expect(completion).toHaveTextContent("You have finished the tour!");
    expect(completion).toHaveTextContent("permanently deleted");
    expect(completion).toHaveTextContent("paid workspace");
    await user.click(within(completion).getByRole("link", { name: "GoDaddy domains for your workspace" }));
    expect(location.pathname).toBe("/store/00000000-0000-4000-8000-00000000b00b");
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
  }, 60000);

  it("stops simulated typing when the person edits the field", async () => {
    vi.mocked(tour.typeInto).mockRestore();
    const input = document.createElement("input"); document.body.append(input);
    const typing = actualType(input, "Default name", 2);
    input.value = "My own name";
    expect(await typing).toBe(false);
    expect(input.value).toBe("My own name"); input.remove();
  });

  it("explains that tasks can be requested in chat for one-off and recurring work", async () => {
    open(S.TASKS, "/workspaces/trial/settings");
    await title("Find scheduled Tasks");
    const explanation = screen.getByRole("region", { name: "Guided tour" });
    expect(explanation).toHaveTextContent("In 30 minutes");
    expect(explanation).toHaveTextContent("Every weekday at 9 am");
    expect(explanation).toHaveTextContent("session chat");
  });

  it("lets the user dismiss completion and keep their conversation", async () => {
    open(S.DONE);
    await title("Make it yours, then Send");
    const path = location.pathname;
    await next("Finish tutorial");
    const completion = await screen.findByRole("dialog", { name: "Tutorial completed" });
    await user.click(within(completion).getByRole("button", { name: "Keep exploring" }));
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
    expect(location.pathname).toBe(path);
  });

  it("keeps the tour open when saving completion fails and allows a retry", async () => {
    open(S.DONE);
    await title("Make it yours, then Send");
    api.state.fail = 503;
    await next("Finish tutorial");
    expect(within(guide()).getByRole("alert")).toHaveTextContent("Could not save completion");
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
    api.state.fail = 0;
    await next("Finish tutorial");
    expect(await screen.findByRole("dialog", { name: "Tutorial completed" })).toBeInTheDocument();
  });

  it("does not notify completion when the user skips", async () => {
    open(S.DONE);
    await title("Make it yours, then Send");
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(false);
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
  });
});

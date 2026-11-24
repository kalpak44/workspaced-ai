import { render, screen, waitFor, within } from "@testing-library/react";
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
  tour.setTourProgress({ step, workspaceId: workspace.id, ...(step === S.DONE ? { sessionId: "finished-change" } : {}) });
  if (step === S.DONE) {
    api.sessions.set(workspace.id, [{ id: "finished-change", title: "My page", path: "/home/workspace/sessions/finished-change", createdAt: new Date().toISOString() }]);
    api.messages.set("finished-change", [{ id: "done", role: "assistant", parts: [{ id: "text", type: "text", text: "The page is updated.", tool: null, status: null }] }]);
    tour.reportChatBusy("finished-change", false);
  }
  globalThis.history.replaceState(null, "", path);
  render(<App />);
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
    const button = within(guide()).getByRole("button", { name: /Next|Finish tutorial/ });
    if (canContinue) expect(button, text + " :: " + screen.getByRole("region", { name: "Guided tour" }).textContent).toBeEnabled(); else expect(button).toBeDisabled();
  });
}

beforeEach(() => {
  api = installFakeApi([workspace]);
  user = userEvent.setup();
  vi.spyOn(tour, "typeInto").mockImplementation((field, text, _delay, signal) => actualType(field, text, 0, signal));
});

describe("guided public-page workflow", () => {
  it("announces workspace readiness and highlights Next only when startup finishes", async () => {
    tour.setTourProgress({ step: S.STARTING, workspaceId: workspace.id });
    const props = { loading: false, onDraft: vi.fn(), onLaunch: vi.fn() };
    const { rerender } = render(<DemoTour {...props} workspaces={[{ ...workspace, status: "INITIALIZING" }]} />);
    await title("Your workspace is starting");
    const button = within(guide()).getByRole("button", { name: "Next" });
    expect(button).toBeDisabled();
    expect(button.parentElement).not.toHaveClass("tour-ready-next");
    expect(within(guide()).getByRole("status")).toHaveTextContent("Preparing the workspace");

    rerender(<DemoTour {...props} workspaces={[workspace]} />);
    await title("Your workspace is ready");
    expect(button).toBeEnabled();
    expect(button.parentElement).toHaveClass("tour-ready-next");
    expect(within(guide()).getByRole("status")).toHaveTextContent("Your workspace is ready. Click Next to continue.");
    await next();
    await title("Open the real recipe Store");
    expect(button.parentElement).not.toHaveClass("tour-ready-next");
  });

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
    await title("Your trial AI token");
    await waitFor(() => expect(api.auth.account.tour?.name).toBe("My own project"));
    expect(api.launched).toHaveLength(0);
  });

  it("resumes the account's tour in a browser that has never seen it", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true, tour: { step: S.OPENROUTER, name: "From elsewhere", draftId: "d-1" } };
    render(<App />);
    expect(await title("Your trial AI token")).toBeInTheDocument();
  });

  it("keeps the place when skipped and resumes there rather than starting again", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true, tour: { step: S.OPENROUTER, name: "Mine", draftId: "d-1" } };
    render(<App />);
    await title("Your trial AI token");
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    await waitFor(() => expect(api.auth.account.tour).toEqual({ step: S.OPENROUTER, name: "Mine", draftId: "d-1", paused: true }));
    expect(screen.queryByRole("region", { name: "Guided tour controls" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Resume the tour" }));
    await title("Your trial AI token");
    await waitFor(() => expect(api.auth.account.tour?.paused).toBe(false));
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
    await title("Your trial AI token");
  });

  it("keeps the tour blocked when the trial connector is unavailable", async () => {
    tour.setTourProgress({ step: S.OPENROUTER, name: "My chosen name" });
    const onDraft = vi.fn();
    const onLaunch = vi.fn();
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={onDraft} onLaunch={onLaunch} />);
    await title("Your trial AI token");
    const select = screen.getByRole("combobox", { name: "Trial AI connector" });
    expect(within(select).getAllByRole("option")).toHaveLength(1);
    await waitFor(() => expect(select).toHaveDisplayValue("Trial connector unavailable"));
    const button = within(guide()).getByRole("button", { name: "Next" });
    expect(button).toBeDisabled();
    await user.click(button);
    expect(onDraft).not.toHaveBeenCalled();
    expect(onLaunch).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Connect OpenRouter" })).not.toBeInTheDocument();
  });

  it("uses the protected trial connector after notice and deletion consent", async () => {
    api.connectors.push({ id: "trial-connector", name: "Trial", provider: "openrouter", trial: true });
    tour.setTourProgress({ step: S.OPENROUTER, name: "My chosen name" });
    const onDraft = vi.fn().mockResolvedValue({ id: "trial" });
    const onLaunch = vi.fn().mockResolvedValue("created");
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={onDraft} onLaunch={onLaunch} />);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Trial AI connector" })).toHaveDisplayValue("Trial"));
    expect(within(guide()).getByRole("button", { name: "Next" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /I acknowledge the third-party AI risks/ }));
    await next();
    await title("Choose the machine for this trial");
    expect(onDraft).toHaveBeenCalledWith(expect.objectContaining({ name: "My chosen name", demo: true }));
    await screen.findByRole("radio", { name: "cx23" });
    expect(within(guide()).getByRole("button", { name: "Create free workspace" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /permanently deleted after four hours/ }));
    await next("Create free workspace");
    expect(onLaunch).toHaveBeenCalledWith("trial", "", "trial-connector");
  });

  it("builds a public page through the Store and chat, then waits for a custom change", async () => {
    api.recipes.rows.push({ id: tour.TOUR_RECIPE_ID, title: "Simple web page", summary: "A little page to make your own", category: "websites", tags: ["demo"], visibility: "PUBLIC", author: { id: "workspaced", firstName: "workspaced", lastName: "" }, createdAt: "2026-11-01T10:00:00Z", publishedAt: "2026-11-01T10:00:00Z", rating: { average: 0, count: 0 }, myRating: null, mine: false, content: "Prepare the page, ask to make it PUBLIC, then verify the HTTP link." } satisfies Recipe);
    open(S.STORE);
    await title("Open the real recipe Store");
    await user.click(screen.getByRole("link", { name: "Store" }));
    await title("Recipes can belong just to you");
    await user.click(await screen.findByRole("tab", { name: "Private" }));
    await title("You can write or import your own");
    const write = screen.getByRole("menuitem", { name: /Write it yourself/ });
    const importFile = screen.getByRole("menuitem", { name: /Import a file/ });
    expect(write).toBeDisabled(); expect(importFile).toBeDisabled();
    await user.click(write); await user.click(importFile);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await next(); await next();
    await title("Find Simple web page");
    await next();
    expect(screen.getByRole("searchbox", { name: "Search recipes" })).toHaveValue("Simple web page");
    await next();
    await title("Read what the recipe will do");
    await waitFor(() => expect(document.querySelector('[data-tour="recipe-overview"]')).toHaveTextContent("Simple web page"));
    expect(screen.getByText("Read the full recipe and code").closest("details")).not.toHaveAttribute("open");
    await next(); await next();
    await title("Apply it in your trial workspace");
    await user.click(within(screen.getByRole("dialog", { name: "Apply Simple web page" })).getByRole("button", { name: "Apply" }));
    await title("Watch the recipe start in chat");
    await waitFor(() => expect(api.prompts).toHaveLength(1));
    const sessionId = api.prompts[0].sessionId;
    await answer(sessionId, "I am preparing the page.", false);
    await answer(sessionId, "Shall I make this page PUBLIC so anyone can open it from the internet? Reply here to let me publish it.");
    await next();
    await title("Ask the assistant to make it public");
    expect(location.pathname).toBe(`/workspaces/trial/sessions/${sessionId}`);
    expect(screen.queryByRole("form", { name: "Open a port" })).not.toBeInTheDocument();
    await next();
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(tour.TOUR_PUBLIC_PROMPT);
    await next();
    expect(api.prompts.at(-1)?.text).toBe(tour.TOUR_PUBLIC_PROMPT);
    await title("Watch your page go live");
    await answer(sessionId, "Your page is ready at http://203.0.113.10/. Anyone can open it from the internet.");
    await next(); await next("My page works");
    await title("Your turn: ask for any change");
    await user.type(screen.getByRole("textbox", { name: "Message" }), "Add a friendly welcome for my friends.");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await title("See your change, then keep exploring");
    expect(within(guide()).getByRole("button", { name: "Finish tutorial" })).toBeDisabled();
    // An idle event can arrive before the new assistant reply is loaded.
    FakeEventSource.emitSession("status", { busy: false });
    await screen.findByRole("button", { name: "Send" });
    expect(within(guide()).getByRole("button", { name: "Finish tutorial" })).toBeDisabled();
    expect(api.tasks.get("trial")?.size ?? 0).toBe(0);
    expect(api.instructions.get("trial")?.size ?? 0).toBe(0);
    expect(api.sent).toHaveLength(0);
    await answer(sessionId, "The welcome is updated. Refresh http://203.0.113.10/ to see it.");
    await next("Finish tutorial");
    const completion = await screen.findByRole("dialog", { name: "Tutorial completed" });
    expect(within(completion).getByRole("link", { name: /Read the Docs/ })).toHaveAttribute("href", tour.TOUR_DOCS_URL);
    expect(api.mail.sent).toHaveLength(0);
    await user.click(within(completion).getByRole("link", { name: "GoDaddy domains for your workspace" }));
    expect(location.pathname).toBe("/store/00000000-0000-4000-8000-00000000b00b");
  }, 30000);

  it("accepts a direct chat reply without a tour Next click", async () => {
    tour.setTourProgress({ step: S.BUILD, workspaceId: workspace.id, sessionId: "direct-confirm" });
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={vi.fn()} onLaunch={vi.fn()} />);
    await title("Watch the recipe start in chat");
    tour.reportTourAction({ type: "message-sent", workspaceId: workspace.id, sessionId: "direct-confirm" });
    await title("Watch your page go live");
    expect(location.pathname).not.toContain("settings");
  });

  it("stops simulated typing when the person edits the field", async () => {
    vi.mocked(tour.typeInto).mockRestore();
    const input = document.createElement("input"); document.body.append(input);
    const typing = actualType(input, "Default name", 2);
    input.value = "My own name";
    expect(await typing).toBe(false);
    expect(input.value).toBe("My own name"); input.remove();
  });

  it("lets the user dismiss completion and keep their conversation", async () => {
    open(S.DONE);
    await title("See your change, then keep exploring");
    const path = location.pathname;
    await next("Finish tutorial");
    const completion = await screen.findByRole("dialog", { name: "Tutorial completed" });
    await user.click(within(completion).getByRole("button", { name: "Keep exploring" }));
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
    expect(location.pathname).toBe(path);
  });

  it("keeps the tour open when saving completion fails and allows a retry", async () => {
    open(S.DONE);
    await title("See your change, then keep exploring");
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
    await title("See your change, then keep exploring");
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(false);
    expect(screen.queryByRole("dialog", { name: "Tutorial completed" })).not.toBeInTheDocument();
  });
});

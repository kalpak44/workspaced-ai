import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { Recipe } from "../src/api/recipesApi";
import type { Workspace } from "../src/api/workspacesApi";
import { publicTourAnswer } from "../src/hooks/useDemoTour";
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
    const button = within(guide()).getByRole("button", { name: /Next|Finish|It works/ });
    if (canContinue) expect(button, text + " :: " + screen.getByRole("region", { name: "Guided tour" }).textContent).toBeEnabled(); else expect(button).toBeDisabled();
  });
}

beforeEach(() => {
  api = installFakeApi([workspace]);
  user = userEvent.setup();
  vi.spyOn(tour, "typeInto").mockImplementation((field, text, _delay, signal) => actualType(field, text, 0, signal));
});

describe("guided public-page workflow", () => {
  it("confirms a predefined public-access question through Next while the tool waits", async () => {
    const id = "public-form";
    api.sessions.set(workspace.id, [{ id, title: "My page", path: "/home/workspace/sessions/public-form", createdAt: new Date().toISOString() }]);
    api.messages.set(id, [{ id: "checkpoint", role: "assistant", parts: [{ id: "text", type: "text", text: "Shall I publish this page PUBLIC so people can open it from the internet?", tool: null, status: null }] }]);
    api.forms.set(id, [{ id: "publish-form", title: "Questions", fields: [{ key: "publish", type: "string", title: "Publish the page?", custom: true, required: true, options: [{ value: "yes", label: "Yes, publish" }, { value: "no", label: "No, keep it private" }] }] }]);
    tour.setTourProgress({ step: S.BUILD, workspaceId: workspace.id, sessionId: id });
    globalThis.history.replaceState(null, "", "/workspaces/trial/sessions/public-form");
    render(<App />);
    await title("Your assistant is on it");
    await waitFor(() => expect(within(guide()).getByRole("button", { name: "Next" })).toBeEnabled());
    expect(screen.getByRole("combobox", { name: /Publish the page/ })).toHaveValue("yes");
    await next();
    await title("Say yes");
    await next();
    await title("Putting your page online");
    expect(api.answers).toEqual([expect.objectContaining({ answer: { publish: "yes" } })]);
  });

  it("offers to resume an existing demo when an older browser failed to save its bookmark", async () => {
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: false, tour: null };
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Resume the tour" }));
    await title("Pick a recipe");
    await waitFor(() => expect(api.auth.account.tour?.workspaceId).toBe(workspace.id));
    expect(api.launched).toHaveLength(0);
  });

  it("recovers an empty failed recipe session through Retry without creating another session", async () => {
    api.sessions.set(workspace.id, [{ id: "retry-recipe", title: "My page", path: "/home/workspace/sessions/retry-recipe", createdAt: new Date().toISOString() }]);
    api.recipes.rows.push({ id: tour.TOUR_RECIPE_ID, title: "Simple web page", summary: "Demo", category: "websites", tags: [], visibility: "PUBLIC", author: { id: "workspaced", firstName: "workspaced", lastName: "" }, createdAt: "2026-11-01T10:00:00Z", publishedAt: null, rating: { average: 0, count: 0 }, myRating: null, mine: false, content: "Build the predefined page" } satisfies Recipe);
    tour.setTourProgress({ step: S.BUILD, workspaceId: workspace.id, sessionId: "retry-recipe" });
    globalThis.history.replaceState(null, "", "/workspaces/trial/sessions/retry-recipe");
    render(<App />);
    await title("Your assistant is on it");
    FakeEventSource.emitSession("error", { message: "Agent is starting. Try again." });
    await waitFor(() => expect(within(guide()).getByRole("button", { name: "Retry" })).toBeEnabled());
    await next("Retry");
    await waitFor(() => expect(api.prompts).toHaveLength(1));
    expect(api.prompts[0].sessionId).toBe("retry-recipe");
    expect(api.prompts[0].text).toContain("Build the predefined page");
    expect(api.sessions.get(workspace.id)).toHaveLength(1);
  });

  it("blocks unrelated navigation and keeping the demo, then restores them on skip", async () => {
    open(S.STARTING);
    await title("It's on, and your assistant is awake");
    expect(screen.getByRole("button", { name: "Keep it", hidden: true })).toBeDisabled();
    const path = location.pathname;
    await user.click(screen.getByRole("link", { name: "Store", hidden: true }));
    expect(location.pathname).toBe(path);
    await user.keyboard("{Meta>}k{/Meta}");
    expect(screen.queryByRole("dialog", { name: /command/i })).not.toBeInTheDocument();
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    expect(screen.getByRole("button", { name: "Keep it" })).toBeEnabled();
    await user.click(screen.getByRole("link", { name: "Store" }));
    expect(location.pathname).toBe("/store");
  });

  it("restores the recipe screen when resuming on an unrelated URL", async () => {
    open(S.REVIEW, "/settings/ai-connectors");
    await title("Here's what it will make");
    await waitFor(() => expect(location.pathname).toBe(`/store/${tour.TOUR_RECIPE_ID}`));
  });

  it("does not claim a page is live when the assistant returns no address", async () => {
    tour.setTourProgress({ step: S.DEPLOY, workspaceId: workspace.id, sessionId: "no-link" });
    tour.reportChatBusy("no-link", false);
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={vi.fn()} onLaunch={vi.fn()} />);
    await title("Putting your page online");
    expect(within(guide()).getByRole("button", { name: "Next" })).toBeDisabled();
    expect(within(guide()).getByRole("status")).toHaveTextContent("No public page address yet");
  });

  it("announces workspace readiness and highlights Next only when startup finishes", async () => {
    tour.setTourProgress({ step: S.STARTING, workspaceId: workspace.id });
    const props = { loading: false, onDraft: vi.fn(), onLaunch: vi.fn() };
    const { rerender } = render(<DemoTour {...props} workspaces={[{ ...workspace, status: "INITIALIZING" }]} />);
    await title("Switching it on");
    const button = within(guide()).getByRole("button", { name: "Next" });
    expect(button).toBeDisabled();
    expect(button.parentElement).not.toHaveClass("tour-ready-next");
    expect(within(guide()).getByRole("status")).toHaveTextContent("Switching on your computer");

    rerender(<DemoTour {...props} workspaces={[workspace]} />);
    await title("It's on, and your assistant is awake");
    expect(button).toBeEnabled();
    expect(button.parentElement).toHaveClass("tour-ready-next");
    expect(within(guide()).getByRole("status")).toHaveTextContent("Your workspace is ready. Click Next to continue.");
    await next();
    await title("Pick a recipe");
    expect(button.parentElement).not.toHaveClass("tour-ready-next");
  });

  it("opens the real naming form and accepts only the predefined name through Next", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true };
    globalThis.history.replaceState(null, "", "/?welcome");
    render(<App />);
    await title("Let's build your first website together");
    expect(document.documentElement.dataset.guidedTour).toBe("true");
    expect(screen.queryByRole("button", { name: "Start the tour" })).not.toBeInTheDocument();
    await next();
    await title("What shall we call it?");
    const name = await screen.findByRole("textbox", { name: "Name" });
    expect(name).toHaveAttribute("readonly");
    await user.type(name, "My own project");
    expect(name).toHaveValue("");
    await user.click(screen.getByRole("button", { name: "Cancel", hidden: true }));
    expect(screen.getByRole("dialog", { name: "New workspace" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "New workspace" })).toBeInTheDocument();
    await next();
    expect(name).toHaveValue(tour.TOUR_WORKSPACE_NAME);
    await next();
    await title("Your assistant comes with free AI credit");
    await waitFor(() => expect(api.auth.account.tour?.name).toBe(tour.TOUR_WORKSPACE_NAME));
    expect(api.launched).toHaveLength(0);
  });

  it("resumes the account's tour in a browser that has never seen it", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true, tour: { step: S.OPENROUTER, name: "From elsewhere", draftId: "d-1" } };
    render(<App />);
    expect(await title("Your assistant comes with free AI credit")).toBeInTheDocument();
  });

  it("keeps the place when skipped and resumes there rather than starting again", async () => {
    api = installFakeApi([]);
    api.auth.account = { ...api.auth.account, role: "USER", demoAvailable: true, tour: { step: S.OPENROUTER, name: "Mine", draftId: "d-1" } };
    render(<App />);
    await title("Your assistant comes with free AI credit");
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    await waitFor(() => expect(api.auth.account.tour).toEqual({ step: S.OPENROUTER, name: "Mine", draftId: "d-1", paused: true }));
    expect(screen.queryByRole("region", { name: "Guided tour controls" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Resume the tour" }));
    await title("Your assistant comes with free AI credit");
    await waitFor(() => expect(api.auth.account.tour?.paused).toBe(false));
  });

  it("demonstrates a default name in the real form without submitting until the next click", async () => {
    open(S.WELCOME, "/");
    await title("Let's build your first website together");
    await next();
    await title("What shall we call it?");
    await next();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue(tour.TOUR_WORKSPACE_NAME);
    expect(screen.getByRole("dialog", { name: "New workspace" })).toBeInTheDocument();
    await next();
    await title("Your assistant comes with free AI credit");
  });

  it("keeps the tour blocked when the trial connector is unavailable", async () => {
    tour.setTourProgress({ step: S.OPENROUTER, name: "My chosen name" });
    const onDraft = vi.fn();
    const onLaunch = vi.fn();
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={onDraft} onLaunch={onLaunch} />);
    await title("Your assistant comes with free AI credit");
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
    await title("Your free workspace is ready to switch on");
    expect(onDraft).toHaveBeenCalledWith(expect.objectContaining({ name: "My chosen name", demo: true }));
    await screen.findByText("Your own cloud computer");
    expect(within(guide()).getByRole("button", { name: "Switch it on" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /lasts four hours/ }));
    await next("Switch it on");
    expect(onLaunch).toHaveBeenCalledWith("trial", "", "trial-connector");
  });

  it("builds a public page through the Store and chat, then waits for a custom change", async () => {
    api.recipes.rows.push({ id: tour.TOUR_RECIPE_ID, title: "Simple web page", summary: "A little page to make your own", category: "websites", tags: ["demo"], visibility: "PUBLIC", author: { id: "workspaced", firstName: "workspaced", lastName: "" }, createdAt: "2026-11-01T10:00:00Z", publishedAt: "2026-11-01T10:00:00Z", rating: { average: 0, count: 0 }, myRating: null, mine: false, content: "Prepare the page, ask to make it PUBLIC, then verify the HTTP link." } satisfies Recipe);
    open(S.STORE);
    await title("Pick a recipe");
    await user.click(screen.getByRole("link", { name: "Store" }));
    // Straight to the recipe: the Store's tabs are explained by using them, not beforehand.
    await title("Find “Simple web page”");
    await next();
    expect(screen.getByRole("searchbox", { name: "Search recipes" })).toHaveValue("Simple web page");
    await next();
    await title("Here's what it will make");
    await waitFor(() => expect(document.querySelector('[data-tour="recipe-overview"]')).toHaveTextContent("Simple web page"));
    expect(screen.getByText("Read the full recipe and code").closest("details")).not.toHaveAttribute("open");
    await next(); await next();
    await title("Choose your workspace");
    await user.click(within(screen.getByRole("dialog", { name: "Apply Simple web page" })).getByRole("button", { name: "Apply" }));
    await title("Your assistant is on it");
    await waitFor(() => expect(api.prompts).toHaveLength(1));
    const sessionId = api.prompts[0].sessionId;
    await answer(sessionId, "I am preparing the page.", false);
    await answer(sessionId, "Shall I make this page PUBLIC so anyone can open it from the internet? Reply here to let me publish it.");
    await next();
    await title("Say yes");
    expect(location.pathname).toBe(`/workspaces/trial/sessions/${sessionId}`);
    expect(screen.queryByRole("form", { name: "Open a port" })).not.toBeInTheDocument();
    await next();
    const confirmation = screen.getByRole("textbox", { name: "Message" });
    expect(confirmation).toHaveValue(tour.TOUR_PUBLIC_PROMPT);
    expect(confirmation).toHaveAttribute("readonly");
    await user.type(confirmation, "Do something else");
    expect(confirmation).toHaveValue(tour.TOUR_PUBLIC_PROMPT);
    await next();
    expect(api.prompts.at(-1)?.text).toBe(tour.TOUR_PUBLIC_PROMPT);
    await title("Putting your page online");
    await answer(sessionId, "Your page is ready at http://203.0.113.10/. Anyone can open it from the internet.");
    await next();
    await title("Your page is live!");
    expect(within(screen.getByRole("region", { name: "Guided tour" })).getByRole("link", { name: "Open my page" })).toHaveAttribute("href", "http://203.0.113.10/");
    await next("It works");
    await title("Now make it yours");
    await user.click(screen.getByRole("button", { name: "Use calm, dark colours" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue("Use calm, dark colours"));
    await user.click(screen.getByRole("button", { name: "Send" }));
    await title("Look again: that was you");
    expect(within(guide()).getByRole("button", { name: "Finish" })).toBeDisabled();
    // An idle event can arrive before the new assistant reply is loaded.
    FakeEventSource.emitSession("status", { busy: false });
    await screen.findByRole("button", { name: "Send" });
    expect(within(guide()).getByRole("button", { name: "Finish" })).toBeDisabled();
    expect(api.tasks.get("trial")?.size ?? 0).toBe(0);
    expect(api.instructions.get("trial")?.size ?? 0).toBe(0);
    expect(api.sent).toHaveLength(0);
    await answer(sessionId, "The welcome is updated. Refresh http://203.0.113.10/ to see it.");
    await next("Finish");
    const completion = await screen.findByRole("dialog", { name: "You built a website!" });
    expect(within(completion).getByRole("list", { name: "What you did" })).toHaveTextContent("Put a web page on the internet");
    expect(api.mail.sent).toHaveLength(0);
    await user.click(within(completion).getByRole("link", { name: "Next: put it on your own domain" }));
    expect(location.pathname).toBe("/store/00000000-0000-4000-8000-00000000b00b");
  }, 30000);

  it("ignores out-of-step chat actions while the assistant builds", async () => {
    tour.setTourProgress({ step: S.BUILD, workspaceId: workspace.id, sessionId: "direct-confirm" });
    render(<DemoTour workspaces={[workspace]} loading={false} onDraft={vi.fn()} onLaunch={vi.fn()} />);
    await title("Your assistant is on it");
    tour.reportTourAction({ type: "message-sent", workspaceId: workspace.id, sessionId: "direct-confirm" });
    await title("Your assistant is on it");
    expect(within(guide()).getByRole("button", { name: "Next" })).toBeDisabled();
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
    await title("Look again: that was you");
    const path = location.pathname;
    await next("Finish");
    const completion = await screen.findByRole("dialog", { name: "You built a website!" });
    await user.click(within(completion).getByRole("button", { name: "Keep exploring" }));
    expect(screen.queryByRole("dialog", { name: "You built a website!" })).not.toBeInTheDocument();
    expect(location.pathname).toBe(path);
    await user.click(screen.getByRole("link", { name: /^Workspaces$/ }));
    await screen.findByRole("heading", { name: /^Workspaces$/ });
    expect(screen.queryByRole("button", { name: "Resume the tour" })).not.toBeInTheDocument();
  });

  it("ends on keeping the demo, which opens the keep dialog", async () => {
    open(S.DONE);
    await title("Look again: that was you");
    await next("Finish");
    const completion = await screen.findByRole("dialog", { name: "You built a website!" });
    await user.click(within(completion).getByRole("button", { name: "Keep this workspace" }));
    expect(await screen.findByRole("dialog", { name: "Keep My blog" })).toHaveTextContent("stays exactly as it is");
  });

  it("keeps the tour open when saving completion fails and allows a retry", async () => {
    open(S.DONE);
    await title("Look again: that was you");
    api.state.fail = 503;
    await next("Finish");
    expect(within(guide()).getByRole("alert")).toHaveTextContent("Could not save completion");
    expect(screen.queryByRole("dialog", { name: "You built a website!" })).not.toBeInTheDocument();
    api.state.fail = 0;
    await next("Finish");
    expect(await screen.findByRole("dialog", { name: "You built a website!" })).toBeInTheDocument();
  });

  it("does not notify completion when the user skips", async () => {
    open(S.DONE);
    await title("Look again: that was you");
    await user.click(within(guide()).getByRole("button", { name: "Skip tour" }));
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(false);
    expect(screen.queryByRole("dialog", { name: "You built a website!" })).not.toBeInTheDocument();
  });
});


describe("predefined public-access answers", () => {
  it("supports a boolean publishing question", () => {
    expect(publicTourAnswer([{ id: "public", title: "Publish this page?", fields: [{ key: "allow", type: "boolean" }] }]))
      .toEqual({ id: "public", answer: { allow: true } });
  });
  it("does not pre-answer an unrelated or unspecified free-text question", () => {
    expect(publicTourAnswer([{ id: "delete", title: "Delete the files?", fields: [{ key: "allow", type: "boolean" }] }])).toBeNull();
    expect(publicTourAnswer([{ id: "public", title: "Publish?", fields: [{ key: "answer", type: "string", custom: true }] }])).toBeNull();
  });
});

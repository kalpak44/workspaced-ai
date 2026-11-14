import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { Recipe } from "../src/api/recipesApi";
import { applyPrompt, parseDraft, serializeDraft } from "../src/lib/recipes";
import { installFakeApi } from "./fakeApi";

vi.mock("@xterm/xterm", () => ({ Terminal: class {} }));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class {} }));

let api: ReturnType<typeof installFakeApi>;

const GRACE = { id: "grace-id", username: "grace", firstName: "Grace", lastName: "Hopper" };

function recipe(overrides: Partial<Recipe> = {}): Recipe {
  return {
    id: "wp",
    title: "WordPress with a public address",
    summary: "WordPress and MariaDB behind the workspace's web server.",
    category: "websites",
    tags: ["wordpress", "docker"],
    visibility: "PUBLIC",
    author: GRACE,
    createdAt: "2026-11-01T10:00:00Z",
    updatedAt: "2026-11-02T10:00:00Z",
    publishedAt: "2026-11-01T10:00:00Z",
    rating: { average: 4.5, count: 2 },
    myRating: null,
    mine: false,
    content: "## Steps\n1. Ask for `DB_PASSWORD` under Secrets.",
    ...overrides,
  };
}

beforeEach(() => {
  api = installFakeApi();
});

function open(path: string) {
  globalThis.history.replaceState(null, "", path);
  render(<App />);
}

describe("recipe store", () => {
  it("shows public recipes as cards and keeps private ones in their own tab", async () => {
    api.recipes.rows.push(recipe(), recipe({ id: "notes", title: "My notes app", visibility: "PRIVATE", author: { ...GRACE, id: "admin-id", username: "admin" }, publishedAt: null }));
    const user = userEvent.setup();
    open("/store");

    const list = await screen.findByRole("list", { name: "Public recipes" });
    expect(within(list).getByText("WordPress with a public address")).toBeInTheDocument();
    expect(within(list).getByText(/Grace Hopper/)).toBeInTheDocument();
    expect(within(list).getByText("4.5 (2)")).toBeInTheDocument();
    expect(within(list).queryByText("My notes app")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Private" }));
    const mine = await screen.findByRole("list", { name: "Private recipes" });
    expect(within(mine).getByText("My notes app")).toBeInTheDocument();
    expect(within(mine).getByLabelText("Private")).toBeInTheDocument();
  });

  it("filters by category and search", async () => {
    api.recipes.rows.push(recipe(), recipe({ id: "n8n", title: "n8n automations", category: "automation", tags: ["n8n"] }));
    const user = userEvent.setup();
    open("/store");
    await screen.findByText("n8n automations");

    await user.click(screen.getByRole("button", { name: "Automation" }));
    await waitFor(() => expect(screen.queryByText("WordPress with a public address")).not.toBeInTheDocument());
    expect(api.recipes.listed.at(-1)).toContain("category=automation");

    await user.click(screen.getByRole("button", { name: "All" }));
    await user.type(screen.getByRole("searchbox", { name: "Search recipes" }), "wordpress");
    await waitFor(() => expect(screen.queryByText("n8n automations")).not.toBeInTheDocument());
    expect(api.recipes.listed.at(-1)).toContain("q=wordpress");
  });

  it("writes a private recipe and opens it", async () => {
    const user = userEvent.setup();
    open("/store");
    await user.click(await screen.findByRole("button", { name: /New recipe/ }));
    await user.click(screen.getByRole("menuitem", { name: /Write it yourself/ }));

    const form = screen.getByRole("form", { name: "New recipe" });
    await user.type(within(form).getByLabelText("Title"), "Uptime Kuma");
    await user.type(within(form).getByLabelText("Tags"), "Monitoring, docker");
    await user.click(within(form).getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(within(form).getByRole("textbox", { name: "Recipe steps Markdown" }), { target: { value: "1. Install it." } });
    await user.click(within(form).getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("heading", { name: "Uptime Kuma", level: 2 })).toBeInTheDocument();
    expect(api.recipes.rows[0]).toMatchObject({ title: "Uptime Kuma", tags: ["monitoring", "docker"], visibility: "PRIVATE", content: "1. Install it." });
    expect(screen.getByText(/Only you can see this recipe/)).toBeInTheDocument();
  });

  it("refuses tags the API would refuse before sending them", async () => {
    const user = userEvent.setup();
    open("/store");
    await user.click(await screen.findByRole("button", { name: /New recipe/ }));
    await user.click(screen.getByRole("menuitem", { name: /Write it yourself/ }));
    const form = screen.getByRole("form", { name: "New recipe" });
    await user.type(within(form).getByLabelText("Tags"), "c++");
    expect(within(form).getByText(/lowercase letters, digits and hyphens/)).toBeInTheDocument();
  });

  it("rates someone else's recipe, and takes the rating back", async () => {
    api.recipes.rows.push(recipe());
    const user = userEvent.setup();
    open("/store/wp");
    const stars = await screen.findByRole("radiogroup", { name: "Your rating" });

    await user.click(within(stars).getByRole("radio", { name: "4 stars" }));
    await waitFor(() => expect(within(stars).getByRole("radio", { name: "4 stars" })).toHaveAttribute("aria-checked", "true"));
    expect(api.recipes.rows[0].myRating).toBe(4);

    await user.click(within(stars).getByRole("radio", { name: "4 stars" }));
    await waitFor(() => expect(api.recipes.rows[0].myRating).toBeNull());
  });

  it("applies a recipe in a new session of the chosen workspace", async () => {
    api.recipes.rows.push(recipe());
    const user = userEvent.setup();
    open("/store/wp");
    await user.click(await screen.findByRole("button", { name: "Apply" }));

    const dialog = screen.getByRole("form", { name: "Apply WordPress with a public address" });
    expect(within(dialog).getByRole("radio", { name: /Starter/ })).toBeChecked();
    await user.click(within(dialog).getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(globalThis.location.pathname).toMatch(/^\/workspaces\/starter\/sessions\//));
    await waitFor(() => expect(api.prompts.at(-1)?.text).toContain('Apply this recipe to the workspace: "WordPress with a public address" by Grace Hopper.'));
    expect(api.prompts.at(-1)?.text).toContain("Ask for `DB_PASSWORD` under Secrets.");
  });

  it("lets the author revise with the agent through a draft in the workspace", async () => {
    api.recipes.rows.push(recipe({ author: { ...GRACE, id: "admin-id", username: "admin" }, mine: true }));
    const user = userEvent.setup();
    open("/store/wp");
    await user.click(await screen.findByRole("button", { name: "Edit with the agent" }));
    const dialog = screen.getByRole("form", { name: "Edit with the agent" });
    await user.type(within(dialog).getByLabelText("What should change?"), "Use PostgreSQL");
    await user.click(within(dialog).getByRole("button", { name: "Start session" }));

    await waitFor(() => expect(globalThis.location.pathname).toMatch(/\/sessions\//));
    const draft = api.recipes.drafts.get("starter")?.get("wordpress-with-a-public-address");
    expect(parseDraft(draft ?? "").fields.recipe).toBe("wp");
    await waitFor(() => expect(api.prompts.at(-1)?.text).toContain("Use PostgreSQL"));
  });

  it("imports the agent's draft, publishes it and removes the draft", async () => {
    api.recipes.drafts.set("starter", new Map([["kuma", "---\ntitle: Uptime Kuma\ncategory: devops\ntags: [monitoring]\nvisibility: public\n---\n\n1. Install it."]]));
    const user = userEvent.setup();
    open("/store");
    await user.click(await screen.findByRole("button", { name: /New recipe/ }));
    await user.click(screen.getByRole("menuitem", { name: /Import a draft/ }));
    await user.click(await screen.findByRole("button", { name: /Uptime Kuma/ }));

    const form = screen.getByRole("form", { name: "New recipe" });
    expect(within(form).getByLabelText("Title")).toHaveValue("Uptime Kuma");
    expect(within(form).getByLabelText("Category")).toHaveValue("devops");
    await user.click(within(form).getByRole("button", { name: "Publish" }));

    expect(await screen.findByRole("heading", { name: "Uptime Kuma", level: 2 })).toBeInTheDocument();
    expect(api.recipes.rows[0]).toMatchObject({ visibility: "PUBLIC", category: "devops", content: "1. Install it." });
    await waitFor(() => expect(api.recipes.drafts.get("starter")?.size).toBe(0));
  });

  it("shows a private recipe of someone else as not found", async () => {
    api.recipes.rows.push(recipe({ visibility: "PRIVATE" }));
    open("/store/wp");
    expect(await screen.findByText("Recipe not found")).toBeInTheDocument();
  });
});

describe("recipe drafts", () => {
  it("round-trips front matter, quoting what could read as something else", () => {
    const fields = { title: "Ghost: a blog", summary: "Fast # simple", category: "websites", tags: ["ghost", "blog"], visibility: "PUBLIC" as const, recipe: "abc" };
    const parsed = parseDraft(serializeDraft(fields, "1. Do it."));
    expect(parsed).toEqual({ fields, body: "1. Do it." });
  });

  it("reads a draft with no front matter as steps alone", () => {
    expect(parseDraft("just steps")).toEqual({ fields: { tags: [] }, body: "just steps" });
  });

  it("puts the recipe after the instructions for applying it", () => {
    expect(applyPrompt("T", "A", "steps").endsWith("---\n\nsteps")).toBe(true);
  });
});

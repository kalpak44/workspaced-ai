import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { Recipe } from "../src/api/recipesApi";
import { applyPrompt, authorName, parseDraft, serializeDraft } from "../src/lib/recipes";
import { installFakeApi } from "./fakeApi";

vi.mock("@xterm/xterm", () => ({ Terminal: class {} }));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class {} }));

let api: ReturnType<typeof installFakeApi>;

const GRACE = { id: "grace-id", firstName: "Grace", lastName: "Hopper" };

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

async function importFile(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.click(await screen.findByRole("button", { name: /New recipe/ }));
  await user.click(screen.getByRole("menuitem", { name: /Import a file/ }));
  await user.upload(screen.getByLabelText("Recipe file to import"), new File([text], "recipe.md", { type: "text/markdown" }));
}

describe("recipe store", () => {
  it("names an author Auth0 knows no name for without a stray space", () => {
    expect(authorName({ firstName: "Unknown", lastName: "" })).toBe("Unknown");
    expect(authorName({ firstName: "Grace", lastName: "Hopper" })).toBe("Grace Hopper");
  });

  it("shows public recipes as cards and keeps private ones in their own tab", async () => {
    api.recipes.rows.push(recipe(), recipe({ id: "notes", title: "My notes app", visibility: "PRIVATE", author: { ...GRACE, id: "admin-id" }, publishedAt: null }));
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
    // Said before saving, since nothing about it can change afterwards.
    expect(within(form).getByText(/can't be edited once it is saved/)).toBeInTheDocument();
    expect(within(form).getByText(/You choose this once/)).toBeInTheDocument();
    await user.type(within(form).getByLabelText("Title"), "Uptime Kuma");
    await user.type(within(form).getByLabelText("Tags"), "Monitoring, docker");
    await user.click(within(form).getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(within(form).getByRole("textbox", { name: "Recipe steps Markdown" }), { target: { value: "1. Install it." } });
    await user.click(within(form).getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("heading", { name: "Uptime Kuma", level: 2 })).toBeInTheDocument();
    expect(api.recipes.rows[0]).toMatchObject({ title: "Uptime Kuma", tags: ["monitoring", "docker"], visibility: "PRIVATE", content: "1. Install it." });
    expect(screen.getByText(/Only you can see this recipe/)).toBeInTheDocument();
    // Private, so it was created rather than published; and the author cannot edit it.
    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.queryByText("Published")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("dates a public recipe by when it was published, and sorts only by rating or age", async () => {
    api.recipes.rows.push(recipe({ createdAt: "2026-10-01T10:00:00Z", publishedAt: "2026-11-01T10:00:00Z" }));
    const user = userEvent.setup();
    open("/store");
    const list = await screen.findByRole("list", { name: "Public recipes" });
    expect(within(list).getByText(/Grace Hopper · published/)).toBeInTheDocument();
    expect([...(screen.getByLabelText("Sort by") as HTMLSelectElement).options].map((o) => o.text)).toEqual(["Top rated", "Newest"]);

    await user.click(within(list).getByRole("button", { name: /WordPress/ }));
    const published = await screen.findByText("Published");
    expect(published.nextElementSibling).toHaveTextContent("Nov 1, 2026");
    expect(screen.queryByText("Updated")).not.toBeInTheDocument();
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
    api.recipes.rows.push(recipe({ author: { ...GRACE, id: "admin-id" }, mine: true }));
    const user = userEvent.setup();
    open("/store/wp");
    await user.click(await screen.findByRole("button", { name: "Revise with the agent" }));
    const dialog = screen.getByRole("form", { name: "Revise with the agent" });
    await user.type(within(dialog).getByLabelText("What should change?"), "Use PostgreSQL");
    await user.click(within(dialog).getByRole("button", { name: "Start session" }));

    await waitFor(() => expect(globalThis.location.pathname).toMatch(/\/sessions\//));
    const draft = api.recipes.drafts.get("starter")?.get("wordpress-with-a-public-address");
    expect(parseDraft(draft ?? "").fields.recipe).toBe("wp");
    await waitFor(() => expect(api.prompts.at(-1)?.text).toContain("Use PostgreSQL"));
  });

  it("publishes a recipe straight from an imported Markdown file", async () => {
    const user = userEvent.setup();
    open("/store");
    await user.click(await screen.findByRole("button", { name: /New recipe/ }));
    expect(screen.queryByRole("menuitem", { name: /Draft with the agent/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: /Import a file/ }));
    const file = new File(["---\ntitle: Uptime Kuma\ncategory: devops\ntags: [monitoring]\nvisibility: public\nrecipe: # keep this id\n---\n\n1. Install it."], "kuma.md", { type: "text/markdown" });
    await user.upload(screen.getByLabelText("Recipe file to import"), file);

    expect(await screen.findByRole("heading", { name: "Uptime Kuma", level: 2 })).toBeInTheDocument();
    expect(api.recipes.rows[0]).toMatchObject({ title: "Uptime Kuma", visibility: "PUBLIC", category: "devops", tags: ["monitoring"], content: "1. Install it." });
  });

  it("imports a revision as a new recipe, and deletes exactly the original it names once asked", async () => {
    api.recipes.rows.push(
      recipe({ author: { ...GRACE, id: "admin-id" }, mine: true, rating: { average: 4, count: 3 } }),
      recipe({ id: "other", title: "Another of mine", author: { ...GRACE, id: "admin-id" }, mine: true }),
    );
    const user = userEvent.setup();
    open("/store");
    // Renamed by the revision: the id, not the title, names the original.
    await importFile(user, "---\ntitle: WordPress on PostgreSQL\nvisibility: public\nrecipe: wp\n---\n\n1. Use PostgreSQL.");

    const dialog = await screen.findByRole("dialog", { name: "Published as a new recipe" });
    expect(dialog).toHaveTextContent("This revises your recipe “WordPress with a public address”");
    expect(api.fetchFn).not.toHaveBeenCalledWith(expect.stringMatching(/\/recipes\/wp$/), expect.objectContaining({ method: "PUT" }));
    expect(api.recipes.rows.map((r) => r.id)).toEqual(["wp", "other", "recipe-1"]);
    await user.click(within(dialog).getByRole("button", { name: "Delete old recipe" }));

    expect(await screen.findByRole("heading", { name: "WordPress on PostgreSQL", level: 2 })).toBeInTheDocument();
    expect(globalThis.location.pathname).toBe("/store/recipe-1");
    expect(api.recipes.rows.map((r) => r.id)).toEqual(["other", "recipe-1"]);
  });

  it("keeps the original when asked, and says when it cannot be deleted", async () => {
    api.recipes.rows.push(recipe({ author: { ...GRACE, id: "admin-id" }, mine: true }));
    const user = userEvent.setup();
    open("/store");
    await importFile(user, "---\ntitle: WordPress, revised\nvisibility: public\nrecipe: wp\n---\n\n1. Use PostgreSQL.");
    const dialog = await screen.findByRole("dialog", { name: "Published as a new recipe" });
    api.recipes.failDelete = true;
    await user.click(within(dialog).getByRole("button", { name: "Delete old recipe" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Could not delete the old recipe");

    await user.click(within(dialog).getByRole("button", { name: "Keep both" }));
    await waitFor(() => expect(globalThis.location.pathname).toBe("/store/recipe-1"));
    expect(api.recipes.rows).toHaveLength(2);
  });

  it.each([
    ["someone else's recipe", "recipe: wp\n"],
    ["a recipe that no longer exists", "recipe: gone\n"],
    ["no recipe at all", ""],
  ])("offers nothing to delete when the draft names %s", async (_, line) => {
    // Grace's, with the same title: only the id could make it the original, and it is not hers to delete here.
    api.recipes.rows.push(recipe());
    const user = userEvent.setup();
    open("/store");
    await importFile(user, `---\ntitle: WordPress with a public address\nvisibility: public\n${line}---\n\n1. Mine.`);
    await waitFor(() => expect(globalThis.location.pathname).toBe("/store/recipe-1"));
    expect(screen.queryByRole("dialog", { name: "Published as a new recipe" })).not.toBeInTheDocument();
    expect(api.recipes.rows.map((r) => r.id)).toEqual(["wp", "recipe-1"]);
  });

  it("refuses a file with no title and says why", async () => {
    const user = userEvent.setup();
    open("/store");
    await user.click(await screen.findByRole("button", { name: /New recipe/ }));
    await user.click(screen.getByRole("menuitem", { name: /Import a file/ }));
    await user.upload(screen.getByLabelText("Recipe file to import"), new File(["1. Install it."], "steps.md", { type: "text/markdown" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not import steps.md: The file has no title");
    expect(api.recipes.rows).toHaveLength(0);
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

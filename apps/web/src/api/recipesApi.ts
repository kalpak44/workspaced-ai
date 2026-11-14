import { send } from "./http";
import { workspaceUrl } from "./workspacesApi";

const RECIPES_URL = "/api/v1/recipes";

export type RecipeVisibility = "PUBLIC" | "PRIVATE";
export type RecipeScope = "public" | "private";
export type RecipeSort = "top" | "newest" | "updated";

/** The API's fixed list; anything else is refused. */
export const RECIPE_CATEGORIES = ["apps", "websites", "automation", "data", "ai", "devops", "other"] as const;
export type RecipeCategory = (typeof RECIPE_CATEGORIES)[number];

export interface RecipeAuthor {
  readonly id: string;
  readonly username: string;
  readonly firstName: string;
  readonly lastName: string;
}

export interface RecipeRating {
  /** 0 while nobody has rated it. */
  readonly average: number;
  readonly count: number;
}

/** What a store card shows; the steps themselves come with the detail. */
export interface RecipeSummary {
  readonly id: string;
  readonly title: string;
  readonly summary: string;
  readonly category: RecipeCategory;
  readonly tags: readonly string[];
  readonly visibility: RecipeVisibility;
  readonly author: RecipeAuthor;
  /** ISO 8601. */
  readonly createdAt: string;
  readonly updatedAt: string;
  /** When it first became public; null while it never has been. */
  readonly publishedAt: string | null;
  readonly rating: RecipeRating;
  readonly myRating: number | null;
  /** Whether the signed-in user wrote it, and so may edit it. */
  readonly mine: boolean;
}

export interface Recipe extends RecipeSummary {
  /** Markdown: the instructions the agent follows when it is applied. */
  readonly content: string;
}

export interface RecipeInput {
  readonly title: string;
  readonly summary: string;
  readonly category: RecipeCategory;
  readonly tags: readonly string[];
  readonly visibility: RecipeVisibility;
  readonly content: string;
}

export interface RecipeQuery {
  readonly scope: RecipeScope;
  readonly q?: string;
  readonly category?: RecipeCategory | null;
  readonly sort?: RecipeSort;
  readonly page?: number;
  readonly size?: number;
}

/** Spring's paged response, as the user list has it. */
export interface RecipePage {
  readonly content: RecipeSummary[];
  readonly page: {
    readonly size: number;
    readonly number: number;
    readonly totalElements: number;
    readonly totalPages: number;
  };
}

/** A recipe the agent wrote into the workspace, waiting to be imported. */
export interface RecipeDraft {
  /** The file's name, without ".md". */
  readonly name: string;
  readonly content: string;
}

// Ids are UUIDs; anything else is refused before it becomes a path segment.
const SAFE_ID = /^[A-Za-z0-9_-]+$/;
// The API's rule for a file in the workspace, as for instructions.
export const DRAFT_NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/;

function recipeUrl(id: string): string {
  if (!SAFE_ID.test(id)) {
    throw new Error("Invalid recipe id");
  }
  return `${RECIPES_URL}/${encodeURIComponent(id)}`;
}

function draftUrl(workspaceId: string, name: string): string {
  if (!DRAFT_NAME.test(name)) {
    throw new Error("Invalid draft name");
  }
  return `${workspaceUrl(workspaceId)}/recipe-drafts/${name}`;
}

export async function listRecipes({ scope, q, category, sort = "top", page = 0, size = 24 }: RecipeQuery): Promise<RecipePage> {
  const query = new URLSearchParams({ scope, sort, page: String(page), size: String(size) });
  if (q?.trim()) query.set("q", q.trim());
  if (category) query.set("category", category);
  return (await send(`${RECIPES_URL}?${query}`)).json();
}

export async function fetchRecipe(id: string): Promise<Recipe> {
  return (await send(recipeUrl(id))).json();
}

export async function createRecipe(input: RecipeInput): Promise<Recipe> {
  return (await send(RECIPES_URL, { method: "POST", body: JSON.stringify(input) })).json();
}

export async function updateRecipe(id: string, input: RecipeInput): Promise<Recipe> {
  return (await send(recipeUrl(id), { method: "PUT", body: JSON.stringify(input) })).json();
}

export async function deleteRecipe(id: string): Promise<void> {
  await send(recipeUrl(id), { method: "DELETE" });
}

export async function rateRecipe(id: string, stars: number): Promise<RecipeRating & { readonly myRating: number | null }> {
  return (await send(`${recipeUrl(id)}/rating`, { method: "PUT", body: JSON.stringify({ stars }) })).json();
}

export async function unrateRecipe(id: string): Promise<void> {
  await send(`${recipeUrl(id)}/rating`, { method: "DELETE" });
}

export async function listDrafts(workspaceId: string): Promise<RecipeDraft[]> {
  return (await send(`${workspaceUrl(workspaceId)}/recipe-drafts`)).json();
}

export async function saveDraft(workspaceId: string, name: string, content: string): Promise<RecipeDraft> {
  return (await send(draftUrl(workspaceId, name), { method: "PUT", body: JSON.stringify({ content }) })).json();
}

export async function deleteDraft(workspaceId: string, name: string): Promise<void> {
  await send(draftUrl(workspaceId, name), { method: "DELETE" });
}

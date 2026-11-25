import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import * as api from "../api/recipesApi";
import { createSession } from "../api/sessionsApi";
import { parseDraft } from "../lib/recipes";
import { queuePrompt } from "./useChat";
import { navigate } from "./useRoute";

export type {
  Recipe,
  RecipeCategory,
  RecipeDraft,
  RecipeInput,
  RecipeScope,
  RecipeSort,
  RecipeSummary,
  RecipeVisibility,
} from "../api/recipesApi";
export { DRAFT_NAME, RECIPE_CATEGORIES } from "../api/recipesApi";

const PAGE_SIZE = 24;

export interface RecipeFilter {
  readonly scope: api.RecipeScope;
  readonly q: string;
  readonly category: api.RecipeCategory | null;
  readonly sort: api.RecipeSort;
}

/** One tab of the store, one page at a time; a new filter starts again from the first page. */
export function useRecipes({ scope, q, category, sort }: RecipeFilter) {
  const [recipes, setRecipes] = useState<api.RecipeSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Answers to an older filter or page are dropped, so a slow one never overwrites a newer one.
  const generation = useRef(0);

  const load = useCallback(
    (next: number) => {
      const current = ++generation.current;
      setLoading(true);
      setError(null);
      api.listRecipes({ scope, q, category, sort, page: next, size: PAGE_SIZE }).then(
        (result) => {
          if (current !== generation.current) return;
          setRecipes(result.content);
          setTotal(result.page.totalElements);
          setPage(next);
          setLoading(false);
        },
        () => {
          if (current !== generation.current) return;
          setError("Could not load the recipes.");
          setLoading(false);
        },
      );
    },
    [scope, q, category, sort],
  );

  useEffect(() => {
    setRecipes([]);
    load(0);
    return () => {
      generation.current++;
    };
  }, [load]);

  return {
    recipes,
    total,
    loading,
    error,
    /** Zero-based, as the API counts them. */
    page,
    pageCount: Math.max(1, Math.ceil(total / PAGE_SIZE)),
    pageSize: PAGE_SIZE,
    goTo: (next: number) => load(next),
    retry: () => load(page),
  };
}

/** One recipe with its steps; `missing` once the API says it is gone or not the user's to see. */
export function useRecipe(id: string) {
  const [recipe, setRecipe] = useState<api.Recipe | null>(null);
  const [missing, setMissing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setRecipe(null);
    setMissing(false);
    setError(null);
    api.fetchRecipe(id).then(
      (loaded) => active && setRecipe(loaded),
      (failure: unknown) => {
        if (!active) return;
        if (failure instanceof ApiError && failure.status === 404) setMissing(true);
        else setError("Could not load the recipe.");
      },
    );
    return () => {
      active = false;
    };
  }, [id]);

  /** Stars from 1 to 5, or null to take the rating back. False when the API refused it. */
  async function rate(stars: number | null): Promise<boolean> {
    try {
      if (stars === null) {
        await api.unrateRecipe(id);
        // The new average is not returned; the detail is fetched again to show it.
        setRecipe(await api.fetchRecipe(id));
      } else {
        const rating = await api.rateRecipe(id, stars);
        setRecipe((current) => current && { ...current, rating: { average: rating.average, count: rating.count }, myRating: rating.myRating });
      }
      return true;
    } catch {
      return false;
    }
  }

  return { recipe, missing, error, loading: recipe === null && !missing && error === null, rate, remove: () => deleteRecipe(id) };
}

/** Creates a recipe. Resolves it, or the API's reason for refusing it. */
export async function createRecipe(input: api.RecipeInput): Promise<api.Recipe | string> {
  try {
    return await api.createRecipe(input);
  } catch (failure) {
    if (failure instanceof ApiError && failure.detail) return failure.detail;
    return "Could not save the recipe.";
  }
}

/** An imported recipe, and the user's own recipe it revises, if its draft named one that still exists. */
export interface Imported {
  readonly created: api.Recipe;
  readonly original: api.Recipe | null;
}

/**
 * Publishes a recipe from a Markdown file in the recipe format: front matter, then the steps. It
 * always creates a new recipe, since none can be changed once saved; a `recipe:` id only names the
 * original. Resolves the new recipe, or why it was refused.
 */
export async function importRecipe(text: string): Promise<Imported | string> {
  const { fields, body } = parseDraft(text);
  if (!fields.title) return "The file has no title in its front matter.";
  if (body === "") return "The file has no steps after its front matter.";
  const category = api.RECIPE_CATEGORIES.find((value) => value === fields.category) ?? "other";
  const created = await createRecipe({
    title: fields.title,
    summary: fields.summary ?? "",
    category,
    tags: fields.tags,
    visibility: fields.visibility ?? "PRIVATE",
    content: body,
  });
  if (typeof created === "string") return created;
  return { created, original: fields.recipe ? await ownRecipe(fields.recipe) : null };
}

// Someone else's recipe, a deleted one or a malformed id is no original to offer for deletion.
async function ownRecipe(id: string): Promise<api.Recipe | null> {
  try {
    const recipe = await api.fetchRecipe(id);
    return recipe.mine ? recipe : null;
  } catch {
    return null;
  }
}

/** False when the API refused it. */
export async function deleteRecipe(id: string): Promise<boolean> {
  try {
    await api.deleteRecipe(id);
    return true;
  } catch {
    return false;
  }
}

/**
 * Opens a new session in the workspace with the prompt queued, the same way the workspace page
 * starts one. A draft to write first goes into the workspace before the session exists. Only
 * applying names the recipe, so a revision is not counted as a use.
 */
export async function startRecipeSession(
  workspaceId: string,
  prompt: string,
  draft?: { readonly name: string; readonly content: string },
  recipeId?: string,
): Promise<boolean> {
  try {
    if (draft) await api.saveDraft(workspaceId, draft.name, draft.content);
    const session = await createSession(workspaceId, recipeId);
    queuePrompt(session.id, prompt);
    navigate({ name: "session", workspaceId, sessionId: session.id });
    return true;
  } catch {
    return false;
  }
}

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import * as api from "../api/recipesApi";
import { createSession } from "../api/sessionsApi";
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

/** One tab of the store, a page at a time; a new filter starts again from the first page. */
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
          setRecipes((shown) => (next === 0 ? result.content : [...shown, ...result.content]));
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
    hasMore: recipes.length < total,
    more: () => load(page + 1),
    retry: () => load(0),
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

  async function remove(): Promise<boolean> {
    try {
      await api.deleteRecipe(id);
      return true;
    } catch {
      return false;
    }
  }

  return { recipe, missing, error, loading: recipe === null && !missing && error === null, rate, remove, replace: setRecipe };
}

/**
 * Creates a recipe, or replaces the one `id` names. Resolves the saved recipe, or the API's reason
 * for refusing it.
 */
export async function saveRecipe(id: string | null, input: api.RecipeInput): Promise<api.Recipe | string> {
  try {
    return id ? await api.updateRecipe(id, input) : await api.createRecipe(input);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 403) return "Only its author can change this recipe.";
    if (failure instanceof ApiError && failure.status === 404) return "This recipe no longer exists.";
    if (failure instanceof ApiError && failure.detail) return failure.detail;
    return "Could not save the recipe.";
  }
}

/** The drafts the agent left in a workspace; pass null while no workspace is chosen. */
export function useRecipeDrafts(workspaceId: string | null) {
  const [drafts, setDrafts] = useState<api.RecipeDraft[] | null>(null);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setDrafts(null);
    setStopped(false);
    setError(null);
    if (workspaceId === null) return undefined;
    api.listDrafts(workspaceId).then(
      (list) => active && setDrafts(list),
      (failure: unknown) => {
        if (!active) return;
        if (failure instanceof ApiError && failure.status === 503) setStopped(true);
        else setError("Could not read the drafts.");
      },
    );
    return () => {
      active = false;
    };
  }, [workspaceId]);

  return { drafts, stopped, error, loading: workspaceId !== null && drafts === null && !stopped && error === null };
}

/** Removes a draft once it is published, so it is not offered for import again. */
export async function removeDraft(workspaceId: string, name: string): Promise<void> {
  try {
    await api.deleteDraft(workspaceId, name);
  } catch {
    // Only tidiness is lost: the recipe is already saved, and the draft can be imported again.
  }
}

/**
 * Opens a new session in the workspace with the prompt queued, the same way the workspace page
 * starts one. A draft to write first goes into the workspace before the session exists.
 */
export async function startRecipeSession(
  workspaceId: string,
  prompt: string,
  draft?: { readonly name: string; readonly content: string },
): Promise<boolean> {
  try {
    if (draft) await api.saveDraft(workspaceId, draft.name, draft.content);
    const session = await createSession(workspaceId);
    queuePrompt(session.id, prompt);
    navigate({ name: "session", workspaceId, sessionId: session.id });
    return true;
  } catch {
    return false;
  }
}

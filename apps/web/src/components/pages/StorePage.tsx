import { Bot, Globe, Lock, Search } from "lucide-react";
import { useEffect, useState } from "react";
import {
  type RecipeCategory,
  type RecipeDraft,
  type RecipeScope,
  type RecipeSort,
  removeDraft,
  saveRecipe,
  startRecipeSession,
  useRecipes,
} from "../../hooks/useRecipes";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { draftPrompt, parseDraft } from "../../lib/recipes";
import { Button, CATEGORIES, Spinner } from "../atoms";
import { EmptyState, PageHeader, RecipeCard, Tabs } from "../molecules";
import { ImportDraftDialog, NewRecipeMenu, WorkspacePickerDialog } from "../organisms";
import { EMPTY_RECIPE, RecipeEditorDialog, type RecipeFormValues } from "../organisms/RecipeEditorDialog";

interface Props {
  readonly workspaces: readonly Workspace[];
}

/** An open editor: what it starts from, the recipe it replaces, and the draft it came from. */
interface Editing {
  readonly initial: RecipeFormValues;
  readonly recipeId: string | null;
  readonly draft?: { readonly workspaceId: string; readonly name: string };
}

const SORTS: ReadonlyArray<{ readonly value: RecipeSort; readonly label: string }> = [
  { value: "top", label: "Top rated" },
  { value: "newest", label: "Newest" },
  { value: "updated", label: "Recently updated" },
];

const CHIP = "shrink-0 rounded-full border px-3 py-1 text-xs font-medium transition-colors";
const CHIP_ON = "border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900";
const CHIP_OFF = "border-zinc-200 text-zinc-600 hover:border-zinc-400 dark:border-zinc-700 dark:text-zinc-300 dark:hover:border-zinc-500";

/**
 * The recipe store: instructions people share for the agent to set something up in a workspace.
 * Public recipes are everyone's to apply and rate; private ones are the author's alone.
 */
export function StorePage({ workspaces }: Props) {
  const [scope, setScope] = useState<RecipeScope>("public");
  const [query, setQuery] = useState("");
  const [q, setQ] = useState("");
  const [category, setCategory] = useState<RecipeCategory | null>(null);
  const [sort, setSort] = useState<RecipeSort>("top");
  const { recipes, total, loading, error, hasMore, more, retry } = useRecipes({ scope, q, category, sort });
  const [editing, setEditing] = useState<Editing | null>(null);
  const [drafting, setDrafting] = useState(false);
  const [importing, setImporting] = useState(false);

  // Searching waits for a pause in typing, so each keystroke is not a request.
  useEffect(() => {
    const timer = setTimeout(() => setQ(query), 250);
    return () => clearTimeout(timer);
  }, [query]);

  const importDraft = (workspaceId: string, draft: RecipeDraft) => {
    const { fields, body } = parseDraft(draft.content);
    setImporting(false);
    setEditing({
      recipeId: fields.recipe ?? null,
      draft: { workspaceId, name: draft.name },
      initial: {
        title: fields.title ?? "",
        summary: fields.summary ?? "",
        category: fields.category ?? EMPTY_RECIPE.category,
        tags: fields.tags,
        visibility: fields.visibility ?? "PRIVATE",
        content: body,
      },
    });
  };

  const filtered = q.trim() !== "" || category !== null;

  return (
    <>
      <PageHeader
        title="Recipe store"
        actions={<NewRecipeMenu onWrite={() => setEditing({ initial: EMPTY_RECIPE, recipeId: null })} onDraftWithAgent={() => setDrafting(true)} onImport={() => setImporting(true)} />}
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-4 py-6 sm:px-6">
          <div>
            <h2 className="text-2xl font-semibold">Recipes</h2>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              Step-by-step instructions the agent follows to set something up in your workspace. Apply one, or share your own.
            </p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <Tabs
              label="Recipes"
              value={scope}
              onChange={setScope}
              tabs={[
                { value: "public", label: "Public", icon: Globe },
                { value: "private", label: "Private", icon: Lock },
              ]}
            />
            <div className="flex min-w-0 gap-2">
              <label className="flex min-w-0 flex-1 items-center gap-2 rounded-md border border-zinc-300 px-3 sm:w-64 sm:flex-none dark:border-zinc-700">
                <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
                <input type="search" aria-label="Search recipes" placeholder="Search recipes…" value={query} onChange={(event) => setQuery(event.target.value)} className="min-h-9 w-full min-w-0 bg-transparent text-sm outline-none" />
              </label>
              <select aria-label="Sort by" value={sort} onChange={(event) => setSort(event.target.value as RecipeSort)} className="rounded-md border border-zinc-300 bg-transparent px-2 text-sm outline-none dark:border-zinc-700 dark:bg-zinc-950">
                {SORTS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </div>
          </div>

          <div role="group" aria-label="Category" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 sm:mx-0 sm:flex-wrap sm:px-0">
            <button type="button" aria-pressed={category === null} onClick={() => setCategory(null)} className={`${CHIP} ${category === null ? CHIP_ON : CHIP_OFF}`}>All</button>
            {CATEGORIES.map((option) => (
              <button
                key={option.value}
                type="button"
                aria-pressed={category === option.value}
                onClick={() => setCategory(category === option.value ? null : (option.value as RecipeCategory))}
                className={`${CHIP} ${category === option.value ? CHIP_ON : CHIP_OFF}`}
              >
                {option.label}
              </button>
            ))}
          </div>

          {error && (
            <div className="flex items-center gap-3">
              <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
              <Button label="Retry" variant="secondary" onClick={retry} />
            </div>
          )}

          {!error && !loading && recipes.length === 0 && (
            <div className="flex min-h-72 flex-col">
              {filtered ? (
                <EmptyState title="Nothing matches" hint="Try another search or category." />
              ) : scope === "public" ? (
                <EmptyState title="No public recipes yet" hint="Be the first: write one, or let a workspace's agent draft it with you." action={{ label: "Write a recipe", onClick: () => setEditing({ initial: { ...EMPTY_RECIPE, visibility: "PUBLIC" }, recipeId: null }) }} />
              ) : (
                <EmptyState title="No private recipes" hint="Private recipes are yours alone: keep your own setups here and apply them to any workspace." action={{ label: "Write a recipe", onClick: () => setEditing({ initial: EMPTY_RECIPE, recipeId: null }) }} />
              )}
            </div>
          )}

          {recipes.length > 0 && (
            <>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">{total === 1 ? "1 recipe" : `${total} recipes`}</p>
              <ul aria-label={scope === "public" ? "Public recipes" : "Private recipes"} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {recipes.map((recipe) => (
                  <RecipeCard key={recipe.id} recipe={recipe} onOpen={() => navigate({ name: "recipe", recipeId: recipe.id })} />
                ))}
              </ul>
            </>
          )}

          {loading && (
            <div className="flex justify-center py-6">
              <Spinner label="Loading recipes" className="size-5 text-zinc-400" />
            </div>
          )}
          {!loading && hasMore && (
            <div className="flex justify-center">
              <Button label="Show more" variant="secondary" onClick={more} />
            </div>
          )}
        </div>
      </div>

      {editing && (
        <RecipeEditorDialog
          title={editing.recipeId ? "Update recipe" : "New recipe"}
          description={editing.draft ? "Review the agent's draft before it is saved. Saving removes the draft from the workspace." : "Recipes are instructions the agent follows step by step."}
          initial={editing.initial}
          onClose={() => setEditing(null)}
          onSave={async (input) => {
            const saved = await saveRecipe(editing.recipeId, input);
            if (typeof saved === "string") return saved;
            if (editing.draft) void removeDraft(editing.draft.workspaceId, editing.draft.name);
            setEditing(null);
            navigate({ name: "recipe", recipeId: saved.id });
            return true;
          }}
        />
      )}
      {drafting && (
        <WorkspacePickerDialog
          title="Draft with the agent"
          description="The agent writes the recipe with you in a new session, and saves it as a draft to import here."
          workspaces={workspaces}
          actionLabel="Start session"
          actionIcon={Bot}
          note={{ label: "What should it set up?", placeholder: "WordPress with MariaDB, on a public address, with daily backups", required: true }}
          onClose={() => setDrafting(false)}
          onConfirm={(workspaceId, idea) => startRecipeSession(workspaceId, draftPrompt(idea))}
        />
      )}
      {importing && <ImportDraftDialog workspaces={workspaces} onPick={importDraft} onClose={() => setImporting(false)} />}
    </>
  );
}

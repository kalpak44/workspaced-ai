import { Globe, Lock, SearchX, Store, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createRecipe, deleteRecipe, importRecipe, type Recipe, type RecipeCategory, type RecipeScope, type RecipeSort, useRecipes } from "../../hooks/useRecipes";
import { navigate } from "../../hooks/useRoute";
import { Button, CATEGORIES, Select, Skeleton } from "../atoms";
import { EmptyState, FilterChips, Modal, ModalBody, ModalFooter, PageHeader, Pagination, RecipeCard, SearchField, Tabs } from "../molecules";
import { NewRecipeMenu } from "../organisms";
import { EMPTY_RECIPE, RecipeEditorDialog, type RecipeFormValues } from "../organisms/RecipeEditorDialog";

const SORTS: ReadonlyArray<{ readonly value: RecipeSort; readonly label: string }> = [
  { value: "top", label: "Top rated" },
  { value: "newest", label: "Newest" },
];

/** A card's outline while its page loads, so the grid keeps its shape. */
function RecipePlaceholder() {
  return (
    <li aria-hidden className="card flex flex-col gap-3 p-4">
      <span className="flex items-center gap-3">
        <Skeleton className="size-9 shrink-0 rounded-lg" />
        <span className="flex flex-1 flex-col gap-2"><Skeleton className="h-3 w-2/3" /><Skeleton className="h-2.5 w-1/3" /></span>
      </span>
      <Skeleton className="h-3 w-full" />
      <Skeleton className="h-3 w-4/5" />
      <Skeleton className="mt-3 h-2.5 w-1/2" />
    </li>
  );
}

/**
 * The recipe store: instructions people share for the agent to set something up in a workspace.
 * Public recipes are everyone's to apply and rate; private ones are the author's alone.
 */
export function StorePage() {
  const [scope, setScope] = useState<RecipeScope>("public");
  const [query, setQuery] = useState("");
  const [q, setQ] = useState("");
  const [category, setCategory] = useState<RecipeCategory | null>(null);
  const [sort, setSort] = useState<RecipeSort>("top");
  const { recipes, total, loading, error, page, pageCount, pageSize, goTo, retry } = useRecipes({ scope, q, category, sort });
  const top = useRef<HTMLDivElement>(null);
  // The form's starting values while it is open.
  const [editing, setEditing] = useState<RecipeFormValues | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  // An imported revision whose original is still the user's to delete.
  const [superseded, setSuperseded] = useState<{ readonly created: Recipe; readonly original: Recipe } | null>(null);
  const [deletingOlder, setDeletingOlder] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // Searching waits for a pause in typing, so each keystroke is not a request.
  useEffect(() => {
    const timer = setTimeout(() => setQ(query), 250);
    return () => clearTimeout(timer);
  }, [query]);

  const importFile = async (file: File) => {
    setImportError(null);
    const saved = await importRecipe(await file.text());
    if (typeof saved === "string") {
      setImportError(`Could not import ${file.name}: ${saved}`);
      return;
    }
    // A revision cannot replace its original, so the original is offered for deletion instead.
    const { created, original } = saved;
    if (original) setSuperseded({ created, original });
    else navigate({ name: "recipe", recipeId: created.id });
  };

  const openCreated = () => {
    if (superseded) navigate({ name: "recipe", recipeId: superseded.created.id });
  };

  const deleteOlder = async () => {
    if (!superseded) return;
    setDeletingOlder(true);
    setDeleteError(null);
    const deleted = await deleteRecipe(superseded.original.id);
    setDeletingOlder(false);
    if (deleted) openCreated();
    else setDeleteError("Could not delete the old recipe.");
  };

  const filtered = q.trim() !== "" || category !== null;
  const clearFilters = () => { setQuery(""); setQ(""); setCategory(null); };
  // A new page starts at the top of the grid rather than wherever the controls left the scroll.
  const turnPage = (next: number) => {
    goTo(next - 1);
    top.current?.scrollIntoView?.({ block: "start", behavior: "smooth" });
  };

  return (
    <>
      <PageHeader
        title="Recipe store"
        description="Step-by-step instructions the agent follows to set something up in your workspace. Apply one, or share your own."
        actions={<NewRecipeMenu onWrite={() => setEditing(EMPTY_RECIPE)} onImport={(file) => void importFile(file)} />}
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div ref={top} className="mx-auto flex w-full max-w-5xl scroll-mt-4 flex-col gap-5 px-4 pt-6 pb-10 sm:px-6">

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
              <SearchField label="Search recipes" placeholder="Search recipes…" value={query} onChange={setQuery} className="flex-1 sm:w-64 sm:flex-none" />
              <Select aria-label="Sort by" value={sort} onChange={(event) => setSort(event.target.value as RecipeSort)} className="shrink-0">
                {SORTS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </div>
          </div>

          <FilterChips label="Category" allLabel="All" value={category} onChange={(value) => setCategory(value as RecipeCategory | null)}
            options={CATEGORIES.map(({ value, label }) => ({ value, label }))} />

          {importError && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 dark:border-rose-900/70 dark:bg-rose-950/40">
              <p role="alert" className="min-w-0 flex-1 text-sm text-rose-700 dark:text-rose-300">{importError}</p>
              <Button label="Dismiss" variant="secondary" onClick={() => setImportError(null)} />
            </div>
          )}

          {error && (
            <div className="flex flex-wrap items-center gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 dark:border-rose-900/70 dark:bg-rose-950/40">
              <p role="alert" className="min-w-0 flex-1 text-sm text-rose-700 dark:text-rose-300">{error}</p>
              <Button label="Retry" variant="secondary" onClick={retry} />
            </div>
          )}

          {!error && !loading && recipes.length === 0 && (
            <div className="card flex min-h-72 flex-col">
              {filtered ? (
                <EmptyState icon={SearchX} title="Nothing matches" hint="Try another search or category." secondaryAction={{ label: "Clear filters", onClick: clearFilters }} />
              ) : scope === "public" ? (
                <EmptyState icon={Store} title="No public recipes yet" hint="Be the first: write one, or import it from a Markdown file." action={{ label: "Write a recipe", onClick: () => setEditing({ ...EMPTY_RECIPE, visibility: "PUBLIC" }) }} />
              ) : (
                <EmptyState icon={Lock} title="No private recipes" hint="Private recipes are yours alone: keep your own setups here and apply them to any workspace." action={{ label: "Write a recipe", onClick: () => setEditing(EMPTY_RECIPE) }} />
              )}
            </div>
          )}

          {loading && recipes.length === 0 && (
            <ul aria-hidden className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {Array.from({ length: 6 }, (_, index) => <RecipePlaceholder key={index} />)}
            </ul>
          )}

          {recipes.length > 0 && (
            <>
              <p className="text-xs text-zinc-500 tabular-nums dark:text-zinc-400" aria-live="polite">
                {total === 1 ? "1 recipe" : `${total} recipes`}
                {filtered && <> · <button type="button" onClick={clearFilters} className="font-medium text-zinc-700 underline-offset-2 hover:underline dark:text-zinc-300">Clear filters</button></>}
              </p>
              <ul aria-label={scope === "public" ? "Public recipes" : "Private recipes"} aria-busy={loading}
                className={`grid gap-3 transition-opacity sm:grid-cols-2 xl:grid-cols-3 ${loading ? "opacity-60" : ""}`}>
                {recipes.map((recipe) => (
                  <RecipeCard key={recipe.id} recipe={recipe} onOpen={() => navigate({ name: "recipe", recipeId: recipe.id })} />
                ))}
              </ul>
              <Pagination label="Recipe pages" noun="recipe" page={page + 1} pageCount={pageCount} onChange={turnPage} disabled={loading}
                range={{ first: page * pageSize + 1, last: page * pageSize + recipes.length, total }} />
            </>
          )}
        </div>
      </div>

      {editing && (
        <RecipeEditorDialog
          title="New recipe"
          description="Recipes are instructions the agent follows step by step."
          initial={editing}
          onClose={() => setEditing(null)}
          onSave={async (input) => {
            const saved = await createRecipe(input);
            if (typeof saved === "string") return saved;
            setEditing(null);
            navigate({ name: "recipe", recipeId: saved.id });
            return true;
          }}
        />
      )}
      {superseded && (
        <Modal title="Published as a new recipe" description="Recipes can't be changed once saved, so the revision did not replace the original." onClose={openCreated}>
          <ModalBody>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">
              This revises your recipe “{superseded.original.title}”. Delete the original, with its ratings, so only the new one is in the store?
            </p>
            {deleteError && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{deleteError}</p>}
          </ModalBody>
          <ModalFooter>
            <Button label="Keep both" variant="secondary" disabled={deletingOlder} onClick={openCreated} autoFocus />
            <Button label="Delete old recipe" variant="danger" icon={Trash2} busy={deletingOlder} onClick={() => void deleteOlder()} />
          </ModalFooter>
        </Modal>
      )}
    </>
  );
}

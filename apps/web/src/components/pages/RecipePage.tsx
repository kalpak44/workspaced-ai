import { Bot, Globe, Lock, Pencil, Play, Trash2 } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { useAccount } from "../../hooks/useAccount";
import { saveRecipe, startRecipeSession, useRecipe } from "../../hooks/useRecipes";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { applyPrompt, authorName, draftName, revisePrompt, serializeDraft } from "../../lib/recipes";
import { Button, CategoryTile, Stars, Tag, categoryLabel } from "../atoms";
import { EmptyState, Markdown, Modal, ModalBody, ModalFooter, PageHeader, RatingInput } from "../molecules";
import { WorkspacePickerDialog } from "../organisms";
import { RecipeEditorDialog } from "../organisms/RecipeEditorDialog";

interface Props {
  readonly recipeId: string;
  readonly workspaces: readonly Workspace[];
  readonly onTitle: (title: string) => void;
}

const DATE = new Intl.DateTimeFormat("en", { dateStyle: "medium" });

type Dialog = "apply" | "edit" | "revise" | "delete" | null;

/** One recipe: its steps, who wrote it, its rating, and Apply. */
export function RecipePage({ recipeId, workspaces, onTitle }: Props) {
  const { recipe, missing, error, loading, rate, remove, replace } = useRecipe(recipeId);
  const { account } = useAccount();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [rating, setRating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const back = () => navigate({ name: "store" });

  useEffect(() => {
    if (recipe) onTitle(recipe.title);
  }, [recipe, onTitle]);

  if (loading || missing || error || !recipe) {
    return (
      <>
        <PageHeader title="Recipe" crumbs={[{ label: "Recipe store", onClick: back }]} />
        {loading ? (
          <EmptyState busy title="Loading recipe…" hint="Fetching it from the store" />
        ) : (
          <EmptyState title={missing ? "Recipe not found" : "Could not load the recipe"} hint={missing ? "It was deleted, or it is private." : "Try again in a moment."} />
        )}
      </>
    );
  }

  const isPublic = recipe.visibility === "PUBLIC";
  const canDelete = recipe.mine || account?.role === "ADMIN";

  return (
    <>
      <PageHeader
        title={recipe.title}
        crumbs={[{ label: "Recipe store", onClick: back }]}
        actions={<Button label="Apply" icon={Play} onClick={() => setDialog("apply")} />}
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <article className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-6 sm:px-6">
          <header className="flex flex-col gap-4">
            <div className="flex items-start gap-4">
              <CategoryTile category={recipe.category} large />
              <div className="min-w-0 flex-1">
                <h2 className="text-2xl font-semibold [overflow-wrap:anywhere]">{recipe.title}</h2>
                {recipe.summary && <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{recipe.summary}</p>}
              </div>
            </div>
            <dl className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
              <Meta label="Author">{authorName(recipe.author)}</Meta>
              <Meta label="Category">{categoryLabel(recipe.category)}</Meta>
              {recipe.publishedAt && <Meta label="Published">{DATE.format(new Date(recipe.publishedAt))}</Meta>}
              <Meta label="Updated">{DATE.format(new Date(recipe.updatedAt))}</Meta>
              <Meta label="Visibility">
                <span className="inline-flex items-center gap-1">
                  {isPublic ? <Globe aria-hidden className="size-3.5" /> : <Lock aria-hidden className="size-3.5" />}
                  {isPublic ? "Public" : "Private"}
                </span>
              </Meta>
            </dl>
            {recipe.tags.length > 0 && (
              <ul aria-label="Tags" className="flex flex-wrap gap-1">
                {recipe.tags.map((tag) => <li key={tag}><Tag label={`#${tag}`} /></li>)}
              </ul>
            )}
          </header>

          <section aria-label="Rating" className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-zinc-200 p-4 dark:border-zinc-800">
            {isPublic ? (
              <>
                <div className="flex items-center gap-3">
                  <span className="text-3xl font-semibold tabular-nums">{recipe.rating.count > 0 ? recipe.rating.average.toFixed(1) : "–"}</span>
                  <div className="flex flex-col gap-0.5">
                    <Stars value={recipe.rating.average} className="size-4" />
                    <span className="text-xs text-zinc-500 dark:text-zinc-400">
                      {recipe.rating.count === 0 ? "No ratings yet" : recipe.rating.count === 1 ? "1 rating" : `${recipe.rating.count} ratings`}
                    </span>
                  </div>
                </div>
                {recipe.mine ? (
                  <p className="text-sm text-zinc-500 dark:text-zinc-400">Others rate your recipe once they have tried it.</p>
                ) : (
                  <div className="flex items-center gap-3">
                    <span className="text-sm text-zinc-500 dark:text-zinc-400">{recipe.myRating ? "Your rating" : "Rate it"}</span>
                    <RatingInput
                      value={recipe.myRating}
                      disabled={rating}
                      onChange={async (stars) => {
                        setRating(true);
                        if (!(await rate(stars))) setNotice("Could not save your rating.");
                        setRating(false);
                      }}
                    />
                  </div>
                )}
              </>
            ) : (
              <p className="flex items-center gap-2 text-sm text-zinc-500 dark:text-zinc-400">
                <Lock aria-hidden className="size-4" />
                Only you can see this recipe. Make it public to share it and collect ratings.
              </p>
            )}
            {recipe.mine || canDelete ? (
              <div className="flex w-full flex-wrap gap-2 border-t border-zinc-100 pt-4 dark:border-zinc-800">
                {recipe.mine && <Button label="Edit" icon={Pencil} variant="secondary" onClick={() => setDialog("edit")} />}
                {recipe.mine && <Button label="Edit with the agent" icon={Bot} variant="secondary" onClick={() => setDialog("revise")} />}
                {canDelete && <Button label="Delete" icon={Trash2} variant="secondary" onClick={() => setDialog("delete")} />}
              </div>
            ) : null}
          </section>
          {notice && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{notice}</p>}

          <section aria-label="Steps" className="rounded-xl border border-zinc-200 dark:border-zinc-800">
            <h3 className="border-b border-zinc-200 px-4 py-3 text-sm font-medium dark:border-zinc-800">What the agent will do</h3>
            <div className="px-4 py-4 text-sm">
              <Markdown text={recipe.content} />
            </div>
          </section>
        </article>
      </div>

      {dialog === "apply" && (
        <WorkspacePickerDialog
          title={`Apply ${recipe.title}`}
          description="A new session starts in the workspace you choose, and its agent follows the recipe, asking you for what it needs."
          workspaces={workspaces}
          actionLabel="Apply"
          actionIcon={Play}
          onClose={() => setDialog(null)}
          onConfirm={(workspaceId) => startRecipeSession(workspaceId, applyPrompt(recipe.title, authorName(recipe.author), recipe.content))}
        />
      )}
      {dialog === "revise" && (
        <WorkspacePickerDialog
          title="Edit with the agent"
          description="The recipe is copied into the workspace as a draft, and a new session opens to revise it. Import it from the store when it is ready."
          workspaces={workspaces}
          actionLabel="Start session"
          actionIcon={Bot}
          note={{ label: "What should change?", placeholder: "Use PostgreSQL instead of MariaDB, and add a weekly backup task" }}
          onClose={() => setDialog(null)}
          onConfirm={(workspaceId, change) => {
            const name = draftName(recipe.title);
            const content = serializeDraft(
              { title: recipe.title, summary: recipe.summary, category: recipe.category, tags: recipe.tags, visibility: recipe.visibility, recipe: recipe.id },
              recipe.content,
            );
            return startRecipeSession(workspaceId, revisePrompt(name, recipe.title, change), { name, content });
          }}
        />
      )}
      {dialog === "edit" && (
        <RecipeEditorDialog
          title="Edit recipe"
          description="Changes apply to everyone who opens it from now on."
          initial={recipe}
          onClose={() => setDialog(null)}
          onSave={async (input) => {
            const saved = await saveRecipe(recipe.id, input);
            if (typeof saved === "string") return saved;
            replace(saved);
            setDialog(null);
            return true;
          }}
        />
      )}
      {dialog === "delete" && (
        <Modal title={`Delete ${recipe.title}?`} description="It leaves the store for everyone, with its ratings." onClose={() => setDialog(null)}>
          <ModalBody>
            <p className="text-sm text-zinc-600 dark:text-zinc-400">Workspaces it was applied to keep everything it set up.</p>
          </ModalBody>
          <ModalFooter>
            <Button label="Cancel" variant="secondary" onClick={() => setDialog(null)} autoFocus />
            <Button
              label="Delete recipe"
              variant="danger"
              icon={Trash2}
              onClick={async () => {
                setDialog(null);
                if (await remove()) back();
                else setNotice("Could not delete the recipe.");
              }}
            />
          </ModalFooter>
        </Modal>
      )}
    </>
  );
}

function Meta({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-zinc-500 dark:text-zinc-400">{label}</dt>
      <dd className="font-medium">{children}</dd>
    </div>
  );
}

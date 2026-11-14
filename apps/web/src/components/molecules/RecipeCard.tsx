import { Lock } from "lucide-react";
import type { RecipeSummary } from "../../hooks/useRecipes";
import { authorName } from "../../lib/recipes";
import { timeAgo } from "../../lib/time";
import { CategoryTile, Stars, Tag, categoryLabel } from "../atoms";

interface Props {
  readonly recipe: RecipeSummary;
  readonly onOpen: () => void;
}

const MAX_TAGS = 3;

/** A recipe's preview in the store: what it sets up, who wrote it and how it is rated. */
export function RecipeCard({ recipe, onOpen }: Props) {
  const extra = recipe.tags.length - MAX_TAGS;
  return (
    <li className="min-w-0">
      <button
        type="button"
        onClick={onOpen}
        className="group flex h-full w-full flex-col gap-3 rounded-xl border border-zinc-200 p-4 text-left transition-colors hover:border-zinc-400 hover:bg-zinc-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-500 dark:border-zinc-800 dark:hover:border-zinc-600 dark:hover:bg-zinc-900/50"
      >
        <span className="flex items-start gap-3">
          <CategoryTile category={recipe.category} />
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-1.5">
              <span className="truncate text-sm font-semibold">{recipe.title}</span>
              {recipe.visibility === "PRIVATE" && <Lock aria-label="Private" className="size-3.5 shrink-0 text-zinc-400" />}
            </span>
            <span className="block text-xs text-zinc-500 dark:text-zinc-400">{categoryLabel(recipe.category)}</span>
          </span>
          {recipe.mine && <Tag label="Yours" />}
        </span>
        <span className="line-clamp-2 min-h-10 text-sm text-zinc-600 dark:text-zinc-400">
          {recipe.summary || <span className="italic text-zinc-400">No description</span>}
        </span>
        {recipe.tags.length > 0 && (
          <span className="flex min-w-0 flex-wrap gap-1">
            {recipe.tags.slice(0, MAX_TAGS).map((tag) => <Tag key={tag} label={`#${tag}`} />)}
            {extra > 0 && <Tag label={`+${extra}`} />}
          </span>
        )}
        <span className="mt-auto flex items-center justify-between gap-2 border-t border-zinc-100 pt-3 text-xs text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
          <span className="min-w-0 truncate">
            {authorName(recipe.author)} · {timeAgo(recipe.updatedAt)}
          </span>
          {recipe.visibility === "PUBLIC" && (
            <span className="flex shrink-0 items-center gap-1">
              <Stars value={recipe.rating.average} />
              <span>{recipe.rating.count > 0 ? `${recipe.rating.average.toFixed(1)} (${recipe.rating.count})` : "No ratings"}</span>
            </span>
          )}
        </span>
      </button>
    </li>
  );
}

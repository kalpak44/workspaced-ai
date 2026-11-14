import { Globe, Lock, Save, Send } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { RecipeCategory, RecipeInput, RecipeVisibility } from "../../hooks/useRecipes";
import { parseTags } from "../../lib/recipes";
import { Button, CATEGORIES, ChoiceCard, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";
// Imported directly, not through the index: the editor is large and only loaded with the store.
import { InstructionsEditor } from "../molecules/InstructionsEditor";

/** What the form starts from: a recipe being edited, a draft the agent wrote, or nothing. */
export interface RecipeFormValues {
  readonly title: string;
  readonly summary: string;
  readonly category: string;
  readonly tags: readonly string[];
  readonly visibility: RecipeVisibility;
  readonly content: string;
}

export const EMPTY_RECIPE: RecipeFormValues = { title: "", summary: "", category: "apps", tags: [], visibility: "PRIVATE", content: "" };

interface Props {
  readonly title: string;
  readonly description: string;
  readonly initial: RecipeFormValues;
  /** True once it was saved; a string is the reason it was refused, shown in the form. */
  readonly onSave: (input: RecipeInput) => Promise<true | string>;
  readonly onClose: () => void;
}

const MAX_TAGS = 8;
const TAG = /^[a-z0-9-]{1,30}$/;

const EXAMPLE = `## Before you start
- Ask the user to add the secret \`DB_PASSWORD\` under Secrets, and restart.
- Check it is set: \`test -n "$DB_PASSWORD"\`.

## Steps
1. Ask which public address to use, then publish it.
2. Install and start the app.
3. Check it answers.

## When it is done
Save an instruction file saying how to reach it.`;

/** Writes a recipe, or changes one, with what the store card and the agent both need. */
export function RecipeEditorDialog({ title, description, initial, onSave, onClose }: Props) {
  const [name, setName] = useState(initial.title);
  const [summary, setSummary] = useState(initial.summary);
  const [category, setCategory] = useState(CATEGORIES.some((c) => c.value === initial.category) ? initial.category : "other");
  const [tags, setTags] = useState(initial.tags.join(", "));
  const [visibility, setVisibility] = useState<RecipeVisibility>(initial.visibility);
  const [content, setContent] = useState(initial.content);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ids = { title: useId(), summary: useId(), category: useId(), tags: useId() };

  const parsedTags = parseTags(tags);
  const tagError =
    parsedTags.length > MAX_TAGS
      ? `Use at most ${MAX_TAGS} tags.`
      : parsedTags.some((tag) => !TAG.test(tag))
        ? "Tags are up to 30 lowercase letters, digits and hyphens."
        : null;
  const valid = name.trim() !== "" && content.trim() !== "" && tagError === null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    const result = await onSave({
      title: name.trim(),
      summary: summary.trim(),
      category: category as RecipeCategory,
      tags: parsedTags,
      visibility,
      content,
    });
    setBusy(false);
    if (result !== true) setError(result);
  };

  return (
    <Modal title={title} description={description} onClose={onClose} wide>
      <form aria-label={title} onSubmit={(event) => void submit(event)} className="flex min-h-0 flex-col">
        <ModalBody>
          <TextField id={ids.title} label="Title" value={name} maxLength={100} required autoFocus placeholder="WordPress with a public address" onChange={(event) => setName(event.target.value)} />
          <TextField id={ids.summary} label="Summary" value={summary} maxLength={300} placeholder="One sentence on what it sets up" hint="Shown on the store card." onChange={(event) => setSummary(event.target.value)} />
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="flex min-w-0 flex-col gap-1.5 text-sm">
              <label htmlFor={ids.category} className="font-medium text-zinc-700 dark:text-zinc-300">Category</label>
              <select id={ids.category} value={category} onChange={(event) => setCategory(event.target.value)} className="w-full rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 dark:border-zinc-700 dark:bg-zinc-900 dark:focus:border-zinc-400 dark:focus:ring-white/10">
                {CATEGORIES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </div>
            <TextField id={ids.tags} label="Tags" value={tags} placeholder="wordpress, docker, mysql" error={tagError} hint="Separated by commas." onChange={(event) => setTags(event.target.value)} />
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">Visibility</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              <ChoiceCard name="visibility" value="PRIVATE" label="Private" detail="Only you can see and apply it" icon={Lock} checked={visibility === "PRIVATE"} onChange={() => setVisibility("PRIVATE")} />
              <ChoiceCard name="visibility" value="PUBLIC" label="Public" detail="Everyone can find, apply and rate it" icon={Globe} checked={visibility === "PUBLIC"} onChange={() => setVisibility("PUBLIC")} />
            </div>
          </fieldset>
          <div className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium text-zinc-700 dark:text-zinc-300">Steps</span>
            <InstructionsEditor fileName="recipe.md" label="Recipe steps" value={content} onChange={setContent} placeholder={EXAMPLE} />
            <span className="text-xs text-zinc-500 dark:text-zinc-400">
              The agent follows these when someone applies the recipe. Ask for secrets and addresses first, then check them.
            </span>
          </div>
          {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={visibility === "PUBLIC" ? "Publish" : "Save"} icon={visibility === "PUBLIC" ? Send : Save} type="submit" disabled={!valid} busy={busy} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

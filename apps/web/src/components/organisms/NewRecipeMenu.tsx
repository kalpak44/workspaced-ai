import { ChevronDown, FileInput, PencilLine, Plus } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import { Menu, MenuItem } from "../molecules";

interface Props {
  readonly onWrite: () => void;
  readonly onImport: (file: File) => void;
}

const ROW = "gap-3 px-3 py-2.5";

/** The two ways to make a recipe: by hand in the editor, or from a Markdown file. */
export function NewRecipeMenu({ onWrite, onImport }: Props) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(root, open, close);

  return (
    <div ref={root} className="relative">
      <button data-tour="new-recipe"
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200"
      >
        <Plus aria-hidden className="size-4" />
        New recipe
        <ChevronDown aria-hidden className="size-3.5 opacity-70" />
      </button>
      {/* Outside the menu, so closing the menu does not unmount the picker before a file is chosen. */}
      <input ref={picker} type="file" accept=".md,.markdown,text/markdown" aria-label="Recipe file to import" className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) onImport(file);
        }} />
      {open && (
        <Menu className="mt-2 w-72 rounded-lg shadow-xl">
          <MenuItem className={ROW} onClick={() => { close(); onWrite(); }}>
            <MenuRow icon={PencilLine} title="Write it yourself" detail="Fill in the steps in an editor" />
          </MenuItem>
          <MenuItem className={ROW} onClick={() => { close(); picker.current?.click(); }}>
            <MenuRow icon={FileInput} title="Import a file" detail="Publish a recipe from a Markdown file" />
          </MenuItem>
        </Menu>
      )}
    </div>
  );
}

function MenuRow({ icon: Icon, title, detail }: { readonly icon: typeof Plus; readonly title: string; readonly detail: string }) {
  return (
    <span className="flex min-w-0 flex-1 items-start gap-3">
      <Icon aria-hidden className="mt-0.5 size-4 shrink-0 text-zinc-500" />
      <span className="min-w-0">
        <span className="block font-medium">{title}</span>
        <span className="block text-xs text-zinc-500 dark:text-zinc-400">{detail}</span>
      </span>
    </span>
  );
}

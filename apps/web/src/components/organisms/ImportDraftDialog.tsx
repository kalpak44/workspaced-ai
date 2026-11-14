import { FileText, PencilLine } from "lucide-react";
import { useId, useState } from "react";
import { type RecipeDraft, useRecipeDrafts } from "../../hooks/useRecipes";
import type { Workspace } from "../../hooks/useWorkspaces";
import { DRAFTS_DIRECTORY, parseDraft } from "../../lib/recipes";
import { Button, Spinner, Tag } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly workspaces: readonly Workspace[];
  readonly onPick: (workspaceId: string, draft: RecipeDraft) => void;
  readonly onClose: () => void;
}

/** Lists the drafts the agent wrote in a workspace, to open one in the editor and publish it. */
export function ImportDraftDialog({ workspaces, onPick, onClose }: Props) {
  const running = workspaces.filter((workspace) => workspace.status === "READY");
  const [workspaceId, setWorkspaceId] = useState<string | null>(running[0]?.id ?? null);
  const { drafts, stopped, error, loading } = useRecipeDrafts(workspaceId);
  const selectId = useId();

  return (
    <Modal title="Import a draft" description={`Recipes the agent saved in ${DRAFTS_DIRECTORY}. You review each before it is saved.`} onClose={onClose}>
      <ModalBody>
        {running.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">None of your workspaces is running. Drafts live in a workspace, so start one first.</p>
        ) : (
          <>
            <div className="flex flex-col gap-1.5 text-sm">
              <label htmlFor={selectId} className="font-medium text-zinc-700 dark:text-zinc-300">Workspace</label>
              <select id={selectId} value={workspaceId ?? ""} onChange={(event) => setWorkspaceId(event.target.value)} className="w-full rounded-md border border-zinc-300 bg-transparent px-3 py-2 outline-none focus:border-zinc-500 focus:ring-2 focus:ring-zinc-900/10 dark:border-zinc-700 dark:bg-zinc-900">
                {running.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}
              </select>
            </div>
            {loading && <Spinner label="Loading drafts" className="size-5 text-zinc-400" />}
            {stopped && <p className="text-sm text-zinc-500 dark:text-zinc-400">That workspace is not running.</p>}
            {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
            {drafts?.length === 0 && (
              <p className="text-sm text-zinc-500 dark:text-zinc-400">
                No drafts here yet. Use <span className="font-medium">Draft with the agent</span> to have it write one.
              </p>
            )}
            {drafts && drafts.length > 0 && (
              <ul aria-label="Drafts" className="flex flex-col gap-2">
                {drafts.map((draft) => {
                  const { fields } = parseDraft(draft.content);
                  return (
                    <li key={draft.name}>
                      <button
                        type="button"
                        onClick={() => workspaceId && onPick(workspaceId, draft)}
                        className="flex w-full items-center gap-3 rounded-lg border border-zinc-200 px-4 py-3 text-left hover:border-zinc-400 hover:bg-zinc-50 dark:border-zinc-700 dark:hover:border-zinc-500 dark:hover:bg-zinc-800/60"
                      >
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200">
                          {fields.recipe ? <PencilLine aria-hidden className="size-4" /> : <FileText aria-hidden className="size-4" />}
                        </span>
                        <span className="flex min-w-0 flex-1 flex-col">
                          <span className="truncate text-sm font-medium">{fields.title || draft.name}</span>
                          <span className="truncate font-mono text-xs text-zinc-500 dark:text-zinc-400">{draft.name}.md</span>
                        </span>
                        {fields.recipe && <Tag label="Revision" />}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </ModalBody>
      <ModalFooter>
        <Button label="Close" variant="secondary" onClick={onClose} />
      </ModalFooter>
    </Modal>
  );
}

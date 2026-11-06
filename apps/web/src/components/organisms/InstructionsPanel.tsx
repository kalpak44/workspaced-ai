import { BookOpen } from "lucide-react";
import { useEffect, useState } from "react";
import { useWorkspaceInstructions } from "../../hooks/useInstructions";
import { navigate } from "../../hooks/useRoute";
import { WORKSPACE_INSTRUCTIONS_EXAMPLE } from "../../lib/instructionExamples";
import { Button, Spinner } from "../atoms";
import { Markdown, SettingsSection } from "../molecules";
// Imported directly, not through the index: the editor is large and only loaded with this panel.
import { InstructionsEditor } from "../molecules/InstructionsEditor";

interface Props {
  readonly workspaceId: string;
}

/**
 * What this workspace's agent is told, in the order it is told it: the harness's guide to the
 * workspace, read-only; the user's general instructions, edited in general settings; then this
 * workspace's own, edited here.
 */
export function InstructionsPanel({ workspaceId }: Props) {
  const { saved, guide, loading, error, retry, save } = useWorkspaceInstructions(workspaceId);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (saved !== null) setDraft(saved);
  }, [saved]);

  async function submit() {
    setSaving(true);
    const result = await save(draft);
    setSaving(false);
    if (!result) setNotice("Could not save the instructions. Please try again.");
    else if (result.applied) setNotice("Saved. The workspace's agent uses them from its next message.");
    else setNotice("Saved. The workspace is not running; it gets them when it next starts.");
  }

  if (loading) {
    return <Spinner label="Loading instructions" className="size-5 text-zinc-400" />;
  }
  if (saved === null) {
    return (
      <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <SettingsSection
        title="Workspace guide"
        description="Given to the agent first, from the workspace image: what survives a restart and where to put tools, data and MCP servers. It cannot be edited."
      >
        {guide === null ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">Start the workspace to see its guide.</p>
        ) : (
          <details className="group rounded-md bg-zinc-50 text-sm dark:bg-zinc-900/60">
            <summary className="flex cursor-pointer items-center gap-2 px-4 py-3 font-medium">
              <BookOpen aria-hidden className="size-4" />
              Show the guide
            </summary>
            <div className="border-t border-zinc-200 px-4 py-3 dark:border-zinc-800">
              <Markdown text={guide} />
            </div>
          </details>
        )}
      </SettingsSection>
      <SettingsSection
        title="Workspace instructions"
        description={
          <>
            Rules for this workspace only, given to the agent after the guide and after your{" "}
            <button type="button" onClick={() => navigate({ name: "general-settings" })} className="underline underline-offset-2">
              general AI instructions
            </button>
            .
          </>
        }
      >
        <InstructionsEditor value={draft} placeholder={WORKSPACE_INSTRUCTIONS_EXAMPLE} onChange={(value) => { setDraft(value); setNotice(""); }} />
        <div className="flex items-center justify-between gap-4 border-t border-zinc-200 pt-4 dark:border-zinc-800">
          <p className="text-xs text-zinc-500 dark:text-zinc-400">{draft.length.toLocaleString()} characters</p>
          <Button label="Save instructions" busy={saving} disabled={draft === saved} onClick={() => void submit()} />
        </div>
        <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
      </SettingsSection>
    </div>
  );
}

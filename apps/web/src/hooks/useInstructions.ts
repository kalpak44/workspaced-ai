import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import {
  deleteInstructions,
  type InstructionFile,
  listInstructions,
  saveInstructions,
} from "../api/instructionsApi";

export type { InstructionFile } from "../api/instructionsApi";
export { INSTRUCTION_NAME } from "../api/instructionsApi";

/**
 * A workspace's instruction files, read from the workspace itself, where the agent may change them
 * too; so they need it running, and `stopped` says when it is not.
 */
export function useInstructions(workspaceId: string) {
  const [files, setFiles] = useState<InstructionFile[] | null>(null);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(() => {
    const current = ++generation.current;
    setFiles(null);
    setStopped(false);
    setError(null);
    listInstructions(workspaceId).then(
      (list) => current === generation.current && setFiles(list),
      (failure: unknown) => {
        if (current !== generation.current) return;
        if (failure instanceof ApiError && failure.status === 503) setStopped(true);
        else setError("Could not load the instructions.");
      },
    );
  }, [workspaceId]);

  useEffect(() => {
    load();
    return () => {
      generation.current++;
    };
  }, [load]);

  /** False when the API refused or could not be reached; the list is left as it was. */
  async function save(name: string, content: string): Promise<boolean> {
    try {
      const saved = await saveInstructions(workspaceId, name, content);
      setFiles((current) =>
        [...(current ?? []).filter((file) => file.name !== name), saved].sort((a, b) => a.name.localeCompare(b.name)),
      );
      return true;
    } catch {
      return false;
    }
  }

  async function remove(name: string): Promise<boolean> {
    try {
      await deleteInstructions(workspaceId, name);
      setFiles((current) => (current ?? []).filter((file) => file.name !== name));
      return true;
    } catch {
      return false;
    }
  }

  return { files, stopped, error, loading: files === null && !stopped && error === null, retry: load, save, remove };
}

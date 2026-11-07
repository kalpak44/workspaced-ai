import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { listVariables, saveVariables, type Variable } from "../api/variablesApi";

export type { Variable } from "../api/variablesApi";
export { MAX_VARIABLE_VALUE, MAX_VARIABLES, VARIABLE_NAME } from "../api/variablesApi";

/**
 * A workspace's variables, read from its .env file, where the agent may change them too; so they
 * need it running, and `stopped` says when it is not.
 */
export function useVariables(workspaceId: string) {
  const [variables, setVariables] = useState<Variable[] | null>(null);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(() => {
    const current = ++generation.current;
    setVariables(null);
    setStopped(false);
    setError(null);
    listVariables(workspaceId).then(
      (list) => current === generation.current && setVariables(list),
      (failure: unknown) => {
        if (current !== generation.current) return;
        if (failure instanceof ApiError && failure.status === 503) setStopped(true);
        else setError("Could not load the variables.");
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
  async function save(next: readonly Variable[]): Promise<boolean> {
    try {
      setVariables(await saveVariables(workspaceId, next));
      return true;
    } catch {
      return false;
    }
  }

  return { variables, stopped, error, loading: variables === null && !stopped && error === null, retry: load, save };
}

import { useEffect, useState } from "react";
import { ApiError } from "../api/http";
import { addSecret, listSecrets, removeSecret, type Secret } from "../api/secretsApi";

export type { Secret } from "../api/secretsApi";
export { MAX_SECRETS, SECRET_NAME } from "../api/secretsApi";

/** "conflict" is a name the workspace already has or its provider sets; "full" is the limit. */
export type AddResult = "added" | "conflict" | "full" | "failed";

/**
 * A workspace's secrets, by name. Like the workspace list, it changes only once the API
 * has accepted the change. Pass null while no workspace's settings are open. `onChanged`
 * hears each accepted add or remove, which the running workspace sees only after a restart.
 */
export function useSecrets(workspaceId: string | null, onChanged?: () => void) {
  const [secrets, setSecrets] = useState<Secret[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    setSecrets([]);
    setError(null);
    if (workspaceId === null) {
      return undefined;
    }
    let active = true;
    setLoading(true);
    listSecrets(workspaceId)
      .then((list) => active && setSecrets(list))
      .catch(() => active && setError("Could not load the secrets"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [workspaceId]);

  const add = (name: string, value: string): Promise<AddResult> => {
    if (workspaceId === null) {
      return Promise.resolve("failed");
    }
    return addSecret(workspaceId, name, value).then(
      (secret): AddResult => {
        setSecrets((current) => [...current, secret]);
        onChanged?.();
        return "added";
      },
      (error_: unknown): AddResult => {
        if (!(error_ instanceof ApiError) || error_.status !== 409) {
          return "failed";
        }
        // Both refusals are a 409; only the detail tells the limit from a taken name.
        return error_.detail?.includes("at most") ? "full" : "conflict";
      },
    );
  };

  const remove = (name: string) => {
    if (workspaceId === null) {
      return;
    }
    const settle = () =>
      setRemoving((current) => {
        const next = new Set(current);
        next.delete(name);
        return next;
      });
    setRemoving((current) => new Set(current).add(name));
    removeSecret(workspaceId, name).then(
      () => {
        setSecrets((current) => current.filter((s) => s.name !== name));
        onChanged?.();
        settle();
      },
      () => {
        settle();
        setError(`Could not remove ${name}`);
      },
    );
  };

  return { secrets, loading, error, removing, dismissError: () => setError(null), add, remove };
}

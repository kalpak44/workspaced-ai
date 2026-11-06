import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import {
  addMcpServer,
  listMcpServers,
  type McpServer,
  type NewMcpServer,
  removeMcpServer,
} from "../api/settingsApi";

export type { McpServer, NewMcpServer } from "../api/settingsApi";
export { MCP_NAME } from "../api/settingsApi";

/** "conflict" is a name the workspace already has; "invalid" one the API refused as malformed. */
export type AddServerResult = "added" | "conflict" | "invalid" | "failed";

/** How often the open panel asks the harness, through the API, how its servers are doing. */
export const MCP_POLL_MS = 5000;

/**
 * A workspace's MCP servers; like the other lists, it changes once the API has accepted it. The
 * harness owns them and connects each in the background, so the list is asked for again while the
 * panel is open and the page visible: a server shown connecting turns connected, or failed,
 * without a reload.
 */
export function useMcpServers(workspaceId: string) {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());
  // A poll answered after a newer one, or overlapping an add or a remove, is dropped, so it
  // cannot bring back a server just removed or hide one just added.
  const generation = useRef(0);
  const mutations = useRef(0);

  const refresh = useCallback(
    async (first: boolean) => {
      if (mutations.current > 0) return;
      const request = ++generation.current;
      try {
        const list = await listMcpServers(workspaceId);
        if (request === generation.current && mutations.current === 0) setServers(list);
      } catch {
        // A failed poll keeps the last list; the harness may only be restarting.
        if (first && request === generation.current) setError("Could not load the MCP servers");
      } finally {
        if (first && request === generation.current) setLoading(false);
      }
    },
    [workspaceId],
  );

  useEffect(() => {
    setLoading(true);
    void refresh(true);
    const poll = globalThis.setInterval(() => {
      if (!document.hidden) void refresh(false);
    }, MCP_POLL_MS);
    return () => {
      globalThis.clearInterval(poll);
      // Anything still in flight belongs to the workspace being left.
      generation.current++;
    };
  }, [refresh]);

  /** Holds polls off while a change is in flight, and drops any answered meanwhile. */
  function mutating<T>(change: Promise<T>): Promise<T> {
    mutations.current++;
    generation.current++;
    return change.finally(() => {
      mutations.current--;
    });
  }

  const add = (server: NewMcpServer): Promise<AddServerResult> =>
    mutating(addMcpServer(workspaceId, server)).then(
      (added): AddServerResult => {
        setServers((current) => [...current, added]);
        return "added";
      },
      (failure: unknown): AddServerResult => {
        if (failure instanceof ApiError && failure.status === 409) return "conflict";
        if (failure instanceof ApiError && failure.status === 400) return "invalid";
        return "failed";
      },
    );

  const remove = (name: string) => {
    const settle = () =>
      setRemoving((current) => {
        const next = new Set(current);
        next.delete(name);
        return next;
      });
    setRemoving((current) => new Set(current).add(name));
    mutating(removeMcpServer(workspaceId, name)).then(
      () => {
        setServers((current) => current.filter((s) => s.name !== name));
        settle();
      },
      () => {
        settle();
        setError(`Could not remove ${name}`);
      },
    );
  };

  return { servers, loading, error, removing, dismissError: () => setError(null), add, remove };
}

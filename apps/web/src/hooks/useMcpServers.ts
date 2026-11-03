import { useEffect, useState } from "react";
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

/** A workspace's MCP servers; like the other lists, it changes once the API has accepted it. */
export function useMcpServers(workspaceId: string) {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    let active = true;
    setLoading(true);
    listMcpServers(workspaceId)
      .then((list) => active && setServers(list))
      .catch(() => active && setError("Could not load the MCP servers"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [workspaceId]);

  const add = (server: NewMcpServer): Promise<AddServerResult> =>
    addMcpServer(workspaceId, server).then(
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
    removeMcpServer(workspaceId, name).then(
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

import { useEffect, useRef, useState } from "react";
import {
  createWorkspace,
  deleteWorkspace,
  EVENTS_URL,
  listWorkspaces,
  renameWorkspace,
  type StatusEvent,
  type Workspace,
  type WorkspaceStatus,
} from "./workspacesApi";

export type { Workspace, WorkspaceStatus } from "./workspacesApi";

export const DEFAULT_NAME = "New Workspace";

/** "New Workspace", then "New Workspace 2", 3… so two never share a name. */
export function uniqueName(base: string, taken: readonly string[]): string {
  if (!taken.includes(base)) {
    return base;
  }
  let n = 2;
  while (taken.includes(`${base} ${n}`)) {
    n += 1;
  }
  return `${base} ${n}`;
}

/**
 * The list follows the server: state changes only after the API has accepted the change,
 * so what is shown is never something a reload would take away.
 */
export function useWorkspaces() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // The latest status seen per id. A status event can beat the create response that
  // introduces its workspace, and this is where it waits until then.
  const latest = useRef(new Map<string, WorkspaceStatus>());

  useEffect(() => {
    // StrictMode runs this twice in development; only the live run may set state.
    let active = true;
    listWorkspaces()
      .then((list) => active && setWorkspaces(list))
      .catch(() => active && setError("Could not load workspaces"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  // Pushed rather than polled. EventSource reconnects by itself, and every connection
  // opens with a full snapshot, so a dropped stream cannot leave a status stale.
  useEffect(() => {
    if (loading) {
      return undefined;
    }
    const source = new EventSource(EVENTS_URL);
    source.addEventListener("status", (event) => {
      const { id, status } = JSON.parse(event.data) as StatusEvent;
      latest.current.set(id, status);
      setWorkspaces((current) => current.map((w) => (w.id === id ? { ...w, status } : w)));
    });
    return () => source.close();
  }, [loading]);

  // Plain functions, not async ones: they are handed to click handlers that expect no
  // return value, so each settles its own promise, failure included.
  const create = (name: string = DEFAULT_NAME) => {
    const unique = uniqueName(
      name,
      workspaces.map((w) => w.name),
    );
    createWorkspace(unique).then(
      (created) => {
        const workspace = { ...created, status: latest.current.get(created.id) ?? created.status };
        setWorkspaces((current) => [...current, workspace]);
        setSelectedId(workspace.id);
      },
      () => setError("Could not create the workspace"),
    );
  };

  const rename = (id: string, name: string) => {
    const trimmed = name.trim();
    if (!trimmed || workspaces.some((w) => w.id === id && w.name === trimmed)) {
      return;
    }
    renameWorkspace(id, trimmed).then(
      (renamed) => setWorkspaces((current) => current.map((w) => (w.id === id ? renamed : w))),
      () => setError("Could not rename the workspace"),
    );
  };

  const remove = (id: string) => {
    deleteWorkspace(id).then(
      () => setWorkspaces((current) => current.filter((w) => w.id !== id)),
      () => setError("Could not delete the workspace"),
    );
  };

  // Falls back to the first workspace, which also covers the selected one being deleted.
  const selected = workspaces.find((w) => w.id === selectedId) ?? workspaces.at(0) ?? null;

  return {
    workspaces,
    selected,
    loading,
    error,
    dismissError: () => setError(null),
    select: setSelectedId,
    create,
    rename,
    remove,
  };
}

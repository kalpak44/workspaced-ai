import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import {
  createDraftWorkspace,
  createWorkspace,
  type DeletedEvent,
  deleteWorkspace,
  EVENTS_URL,
  launchWorkspace,
  listWorkspaces,
  type NewWorkspace,
  renameWorkspace,
  restartWorkspace,
  type StatusEvent,
  type Workspace,
  type WorkspaceStatus,
} from "../api/workspacesApi";
import { WORKSPACE_SETTINGS_CHANGED } from "../lib/workspaceChanges";
import { BROWSER_TIME_ZONE } from "../lib/timeZones";

export type { NewWorkspace, Workspace, WorkspaceStatus } from "../api/workspacesApi";

/** "rejected" is the provider refusing the key, which the form can point at; "failed" is anything else. */
/**
 * `soldOut`: the size chosen ran out before the VM could be made. `noFunds`: the balance does not
 * cover a month of it. Nothing was created for either.
 */
export type CreateResult = "created" | "rejected" | "soldOut" | "noFunds" | "failed";

/** A draft's id, or why there is none; a draft has no key yet, so it cannot be "rejected". */
export type DraftResult = { readonly id: string } | Exclude<CreateResult, "created" | "rejected">;

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
  // Ids whose delete is in flight; the API answers only once the pod is gone.
  const [deleting, setDeleting] = useState<ReadonlySet<string>>(new Set());
  // The latest status seen per id. A status event can beat the create response that
  // introduces its workspace, and this is where it waits until then.
  const latest = useRef(new Map<string, WorkspaceStatus>());

  useEffect(() => {
    // StrictMode runs this twice in development; only the live run may set state.
    let active = true;
    // The list and the end of loading are set together: a render between them would show the
    // workspace's page without its header, then remount it once loading ended.
    listWorkspaces()
      .then((list) => {
        if (!active) return;
        setWorkspaces(list);
        setLoading(false);
      })
      .catch(() => {
        if (!active) return;
        setError("Could not load workspaces");
        setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    const refresh = () => { void listWorkspaces().then(list => { if (active) setWorkspaces(list); }).catch(() => { if (active) setError("Could not refresh workspace settings"); }); };
    globalThis.addEventListener(WORKSPACE_SETTINGS_CHANGED, refresh);
    return () => { active = false; globalThis.removeEventListener(WORKSPACE_SETTINGS_CHANGED, refresh); };
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
    // Another tab's delete; this tab's own delete removes the workspace on its response.
    source.addEventListener("deleted", (event) => {
      const { id } = JSON.parse(event.data) as DeletedEvent;
      setWorkspaces((current) => current.filter((w) => w.id !== id));
    });
    return () => source.close();
  }, [loading]);

  // Plain functions, not async ones: they are handed to click handlers that expect no
  // return value, so each settles its own promise, failure included.
  // A rejected key, a sold-out size or a short balance is left to the form to show; any other
  // failure goes to the banner.
  const refusal = (error_: unknown): Exclude<CreateResult, "created"> => {
    if (error_ instanceof ApiError && error_.status === 422) {
      return "rejected";
    }
    if (error_ instanceof ApiError && error_.status === 409) {
      return "soldOut";
    }
    if (error_ instanceof ApiError && error_.status === 402) {
      return "noFunds";
    }
    setError("Could not create the workspace");
    return "failed";
  };

  const added = (created: Workspace, onCreated?: (workspace: Workspace) => void): CreateResult => {
    const workspace = { ...created, status: latest.current.get(created.id) ?? created.status };
    setWorkspaces((current) => [...current, workspace]);
    setSelectedId(workspace.id);
    onCreated?.(workspace);
    return "created";
  };

  /** `onCreated` hears the new workspace, e.g. to open it. */
  const create = (
    request: NewWorkspace,
    onCreated?: (workspace: Workspace) => void,
  ): Promise<CreateResult> =>
    createWorkspace({ timeZone: BROWSER_TIME_ZONE, ...request, name: request.name.trim(), apiKey: request.apiKey.trim() }).then(
      (created) => added(created, onCreated),
      refusal,
    );

  /** Reserves the id a provider key can be labelled with; nothing is listed or charged yet. */
  const draft = (request: Omit<NewWorkspace, "apiKey">): Promise<DraftResult> =>
    createDraftWorkspace({ timeZone: BROWSER_TIME_ZONE, ...request, name: request.name.trim() }).then(
      ({ id }) => ({ id }),
      (error_: unknown) => refusal(error_) as Exclude<CreateResult, "created" | "rejected">,
    );

  /** Gives a draft its key and VM; it then joins the list as {@link create} would add it. */
  const launch = (id: string, apiKey: string, onCreated?: (workspace: Workspace) => void, connectorId?: string): Promise<CreateResult> =>
    launchWorkspace(id, apiKey.trim(), connectorId).then((created) => added(created, onCreated), refusal);

  // Best effort: the API deletes a draft nobody launched on its own after a while.
  const discardDraft = (id: string) => {
    deleteWorkspace(id).catch(() => undefined);
  };

  /** A name for a new workspace that no existing one already has. */
  const suggestName = (base: string = DEFAULT_NAME) =>
    uniqueName(
      base,
      workspaces.map((w) => w.name),
    );

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

  // Shown starting from the click, ahead of the API: the event stream then follows the new pod
  // to READY, and a refused restart puts back the status it had.
  const restart = (id: string) => {
    const before = workspaces.find((w) => w.id === id)?.status;
    if (before === "INITIALIZING") {
      return;
    }
    patch(id, { status: "INITIALIZING" });
    restartWorkspace(id).then(() => patch(id, { restartRequired: false })).catch(() => {
      if (before) {
        patch(id, { status: latest.current.get(id) ?? before });
      }
      setError("Could not restart the workspace");
    });
  };

  const remove = (id: string) => {
    const settle = () =>
      setDeleting((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    setDeleting((current) => new Set(current).add(id));
    deleteWorkspace(id).then(
      () => {
        setWorkspaces((current) => current.filter((w) => w.id !== id));
        settle();
      },
      () => {
        settle();
        setError("Could not delete the workspace");
      },
    );
  };

  /** A change the API has already accepted elsewhere, e.g. a new model from the settings page. */
  const patch = (id: string, changes: Partial<Workspace>) =>
    setWorkspaces((current) => current.map((w) => (w.id === id ? { ...w, ...changes } : w)));

  // Falls back to the first workspace, which also covers the selected one being deleted.
  const selected = workspaces.find((w) => w.id === selectedId) ?? workspaces.at(0) ?? null;

  return {
    workspaces,
    selected,
    loading,
    deleting,
    error,
    dismissError: () => setError(null),
    select: setSelectedId,
    create,
    draft,
    launch,
    discardDraft,
    suggestName,
    rename,
    restart,
    remove,
    patch,
  };
}

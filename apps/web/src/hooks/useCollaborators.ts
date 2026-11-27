import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import {
  type Collaborator,
  inviteCollaborator,
  listCollaborators,
  removeCollaborator,
  revokeInvitation,
} from "../api/collaboratorsApi";
import type { Workspace } from "../api/workspacesApi";
import { useAccount } from "./useAccount";

export type { Collaborator, CollaboratorStatus } from "../api/collaboratorsApi";

/** The reason the API gave for refusing an invitation, worded for the form. */
export type InviteResult = { readonly ok: true } | { readonly ok: false; readonly message: string };

/** Whether the signed-in user is on this workspace by invitation rather than as its owner. */
export function useIsShared(workspace: Pick<Workspace, "ownerId">): boolean {
  const { account } = useAccount();
  return !!account && !!workspace.ownerId && workspace.ownerId !== account.id;
}

/** Who is on a workspace, and inviting, revoking and removing them. */
export function useCollaborators(workspaceId: string) {
  const [collaborators, setCollaborators] = useState<Collaborator[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Rows whose removal is in flight, by user or invitation id.
  const [removing, setRemoving] = useState<ReadonlySet<string>>(new Set());
  const generation = useRef(0);

  const load = useCallback(() => {
    const current = ++generation.current;
    listCollaborators(workspaceId).then(
      (list) => current === generation.current && setCollaborators(list),
      () => current === generation.current && setError("Could not load the collaborators."),
    );
  }, [workspaceId]);

  useEffect(() => {
    setCollaborators(null);
    setError(null);
    load();
    return () => {
      generation.current++;
    };
  }, [load]);

  async function invite(email: string): Promise<InviteResult> {
    try {
      await inviteCollaborator(workspaceId, email.trim());
      load();
      return { ok: true };
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 400) return { ok: false, message: "Enter a valid email address" };
      if (failure instanceof ApiError && failure.detail && [409, 429].includes(failure.status)) {
        return { ok: false, message: failure.detail.charAt(0).toUpperCase() + failure.detail.slice(1) };
      }
      return { ok: false, message: "Could not send the invitation" };
    }
  }

  const settle = (id: string) =>
    setRemoving((current) => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });

  /** An open invitation by its id, or a collaborator by their user id. */
  async function remove(target: Collaborator): Promise<boolean> {
    const id = target.invitationId ?? target.userId;
    if (!id) return false;
    setRemoving((current) => new Set(current).add(id));
    try {
      if (target.invitationId) await revokeInvitation(workspaceId, target.invitationId);
      else await removeCollaborator(workspaceId, id);
      setCollaborators((current) => current?.filter((c) => (c.invitationId ?? c.userId) !== id) ?? null);
      return true;
    } catch {
      setError(target.invitationId ? "Could not revoke the invitation." : "Could not remove the collaborator.");
      return false;
    } finally {
      settle(id);
    }
  }

  return {
    collaborators: collaborators ?? [],
    loading: collaborators === null && error === null,
    error,
    dismissError: () => setError(null),
    removing,
    invite,
    remove,
  };
}

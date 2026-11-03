import { useEffect, useState } from "react";
import { createSession, deleteSession, listSessions, type Session } from "../api/sessionsApi";

export type { Session } from "../api/sessionsApi";

/**
 * A workspace's chat sessions. Like the workspace list, it changes only once the API has
 * accepted the change. Pass null while the workspace has no running harness to ask.
 */
export function useSessions(workspaceId: string | null) {
  const [sessions, setSessions] = useState<Session[]>([]);
  // Loading from the first render, so nothing reads "no sessions" before the fetch began.
  const [loading, setLoading] = useState(workspaceId !== null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<ReadonlySet<string>>(new Set());

  useEffect(() => {
    setSessions([]);
    setError(null);
    if (workspaceId === null) {
      setLoading(false);
      return undefined;
    }
    let active = true;
    setLoading(true);
    listSessions(workspaceId)
      .then((list) => active && setSessions(list))
      .catch(() => active && setError("Could not load the sessions"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [workspaceId]);

  /** Resolves the new session once it exists, or null when the API refused it. */
  const create = (): Promise<Session | null> => {
    if (workspaceId === null) {
      return Promise.resolve(null);
    }
    return createSession(workspaceId).then(
      (session) => {
        setSessions((current) => [...current, session]);
        return session;
      },
      () => {
        setError("Could not start a session. Is the workspace ready?");
        return null;
      },
    );
  };

  const remove = (id: string) => {
    if (workspaceId === null) {
      return;
    }
    const settle = () =>
      setDeleting((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    setDeleting((current) => new Set(current).add(id));
    deleteSession(workspaceId, id).then(
      () => {
        setSessions((current) => current.filter((s) => s.id !== id));
        settle();
      },
      () => {
        settle();
        setError("Could not delete the session");
      },
    );
  };

  /** The agent named a session; nothing to send, the API stored it already. */
  const retitle = (id: string, title: string) =>
    setSessions((current) => current.map((s) => (s.id === id ? { ...s, title } : s)));

  return {
    sessions,
    loading,
    deleting,
    error,
    dismissError: () => setError(null),
    create,
    remove,
    retitle,
  };
}

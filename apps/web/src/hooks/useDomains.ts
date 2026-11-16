import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { attachDomain, detachDomain, type DnsRecord, listDomains, type WorkspaceDomain } from "../api/domainsApi";

export type { DnsRecord, WorkspaceDomain } from "../api/domainsApi";

// The API checks DNS about once a minute; asking more often than this shows nothing new.
const POLL_MS = 15_000;

/** Why an attach was refused, in the user's terms; a 400 carries the reason itself. */
function reason(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 400 && failure.detail) return failure.detail;
  if (failure instanceof ApiError && failure.status === 409) return "The workspace has no VM yet. Try again once it has started.";
  if (failure instanceof ApiError && failure.status === 503 && failure.detail) return failure.detail;
  return "Could not add the domain. Please try again.";
}

/**
 * The workspace's own domains. While any is waiting for DNS the list is fetched again now and
 * then, so it turns live on screen without a reload.
 */
export function useDomains(workspaceId: string) {
  const [domains, setDomains] = useState<WorkspaceDomain[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);

  const refresh = useCallback(async () => {
    const current = generation.current;
    try {
      const list = await listDomains(workspaceId);
      if (generation.current === current) { setDomains(list); setError(null); }
    } catch {
      if (generation.current === current) setError("Could not load the domains.");
    }
  }, [workspaceId]);

  useEffect(() => {
    generation.current++;
    setDomains(null);
    setError(null);
    void refresh();
    return () => { generation.current++; };
  }, [refresh, attempt]);

  const waiting = domains?.some((d) => d.status === "PENDING") ?? false;
  useEffect(() => {
    if (!waiting) return;
    const poll = globalThis.setInterval(() => { if (!document.hidden) void refresh(); }, POLL_MS);
    return () => globalThis.clearInterval(poll);
  }, [waiting, refresh]);

  /** Answers the problem to show, or null once it is added. */
  async function attach(name: string): Promise<string | null> {
    setBusy(true);
    try {
      const added = await attachDomain(workspaceId, name);
      setDomains((list) => [...(list ?? []).filter((d) => d.id !== added.id), added]);
      return null;
    } catch (failure) {
      return reason(failure);
    } finally {
      setBusy(false);
    }
  }

  /** Answers the records it had, or null when it could not be removed. */
  async function detach(id: string): Promise<DnsRecord[] | null> {
    setBusy(true);
    try {
      const records = await detachDomain(workspaceId, id);
      setDomains((list) => list?.filter((d) => d.id !== id) ?? null);
      return records;
    } catch (failure) {
      // Already gone, e.g. removed in another tab: the list just catches up.
      if (failure instanceof ApiError && failure.status === 404) {
        setDomains((list) => list?.filter((d) => d.id !== id) ?? null);
        return [];
      }
      return null;
    } finally {
      setBusy(false);
    }
  }

  return { domains, error, busy, attach, detach, retry: () => setAttempt((value) => value + 1) };
}

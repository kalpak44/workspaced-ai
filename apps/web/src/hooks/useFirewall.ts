import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import {
  type FirewallRule,
  getFirewall,
  setFirewallAgentAllowed,
  setFirewallRules,
  type WorkspaceFirewall,
} from "../api/firewallApi";

export type { FirewallProtocol, FirewallRule, WorkspaceFirewall } from "../api/firewallApi";

/** Why a change was refused, in the user's terms; a 400 carries the reason itself. */
function reason(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 400 && failure.detail) return failure.detail;
  if (failure instanceof ApiError && failure.status === 409) return "The workspace has no VM yet. Try again once it has started.";
  return "Could not change the firewall. Please try again.";
}

/** The workspace VM's address and firewall, as the API holds them. */
export function useFirewall(workspaceId: string) {
  const [firewall, setFirewall] = useState<WorkspaceFirewall | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    const current = ++generation.current;
    setFirewall(null);
    setLoading(true);
    setError(null);
    setSaving(false);
    getFirewall(workspaceId)
      .then((value) => { if (generation.current === current) setFirewall(value); })
      .catch((failure: unknown) => {
        if (generation.current !== current) return;
        setError(failure instanceof ApiError && failure.status === 409
          ? "The workspace has no VM yet. Try again once it has started."
          : "Could not load the firewall.");
      })
      .finally(() => { if (generation.current === current) setLoading(false); });
    return () => { generation.current++; };
  }, [workspaceId, attempt]);

  /** Answers the problem to show, or null once it is saved. */
  async function persist<T>(change: () => Promise<T>, apply: (value: T) => void): Promise<string | null> {
    const current = generation.current;
    setSaving(true);
    try {
      const value = await change();
      if (generation.current === current) apply(value);
      return null;
    } catch (failure) {
      return reason(failure);
    } finally {
      if (generation.current === current) setSaving(false);
    }
  }

  const saveRules = (rules: readonly FirewallRule[]) =>
    persist(() => setFirewallRules(workspaceId, rules), setFirewall);

  const saveAgentAllowed = (allowed: boolean) =>
    persist(
      () => setFirewallAgentAllowed(workspaceId, allowed),
      () => setFirewall((value) => value && { ...value, agentAllowed: allowed }),
    );

  return { firewall, loading, saving, error, saveRules, saveAgentAllowed, retry: () => setAttempt((value) => value + 1) };
}

import { useCallback, useEffect, useRef, useState } from "react";
import { createTerminal, deleteTerminal, listTerminals } from "../api/terminalsApi";
import type { TerminalSession } from "../api/terminalsApi";

export type { TerminalSession } from "../api/terminalsApi";
export { terminalSocketUrl } from "../api/terminalsApi";

/** OpenCode owns terminal state; polling also picks up exits and changes from other browsers. */
export function useTerminals(workspaceId: string | null) {
  const [terminals, setTerminals] = useState<TerminalSession[]>([]);
  const [loading, setLoading] = useState(workspaceId !== null);
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const mutations = useRef(0);
  const scope = useRef(workspaceId);

  const refresh = useCallback(async () => {
    if (!workspaceId || mutations.current > 0) return;
    const request = ++generation.current;
    try {
      const list = await listTerminals(workspaceId);
      if (request === generation.current) setTerminals(list);
    } catch {
      if (request === generation.current) setError("Could not load terminals. Check the workspace connection and try again.");
    } finally {
      if (request === generation.current) setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    scope.current = workspaceId;
    setTerminals([]);
    setError(null);
    setCreating(false);
    setRemoving(null);
    setLoading(workspaceId !== null);
    void refresh();
    const poll = globalThis.setInterval(() => { if (!document.hidden) void refresh(); }, 5000);
    return () => { ++generation.current; scope.current = null; globalThis.clearInterval(poll); };
  }, [workspaceId, refresh]);

  async function create(): Promise<TerminalSession | null> {
    if (!workspaceId || mutations.current > 0) return null;
    ++generation.current;
    ++mutations.current;
    setCreating(true);
    setError(null);
    let number = 1;
    while (terminals.some((terminal) => terminal.title === `Terminal ${number}`)) ++number;
    try {
      const terminal = await createTerminal(workspaceId, `Terminal ${number}`);
      if (scope.current !== workspaceId) return null;
      setTerminals((current) => [...current, terminal]);
      setLoading(false);
      return terminal;
    } catch {
      if (scope.current === workspaceId) setError("Could not create the terminal. Please try again.");
      return null;
    } finally {
      --mutations.current;
      if (scope.current === workspaceId) setCreating(false);
    }
  }

  async function remove(id: string): Promise<boolean> {
    if (!workspaceId || mutations.current > 0) return false;
    ++generation.current;
    ++mutations.current;
    setRemoving(id);
    setError(null);
    try {
      await deleteTerminal(workspaceId, id);
      if (scope.current !== workspaceId) return false;
      setTerminals((current) => current.filter((terminal) => terminal.id !== id));
      return true;
    } catch {
      if (scope.current === workspaceId) setError("Could not delete the terminal. It has been kept in the list.");
      return false;
    } finally {
      --mutations.current;
      if (scope.current === workspaceId) setRemoving(null);
    }
  }

  return { terminals, loading, creating, removing, error, refresh, create, remove, dismissError: () => setError(null) };
}

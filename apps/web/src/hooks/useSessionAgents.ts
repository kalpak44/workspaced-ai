import { useCallback, useEffect, useRef, useState } from "react";
import { getSessionAgents, setSessionAgent, type AgentSelection } from "../api/sessionAgentsApi";

export type { AgentSelection, SessionAgent } from "../api/sessionAgentsApi";

/** Read the selection from OpenCode and update only after the switch is acknowledged. */
export function useSessionAgents(workspaceId: string | null, sessionId: string | null) {
  const [selection, setSelection] = useState<AgentSelection | null>(null);
  const [loading, setLoading] = useState(workspaceId !== null && sessionId !== null);
  const [saving, setSaving] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const switching = useRef(false);

  const reload = useCallback(async () => {
    if (!workspaceId || !sessionId || switching.current) return;
    const request = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const result = await getSessionAgents(workspaceId, sessionId);
      if (request === generation.current) setSelection(result);
    } catch {
      if (request === generation.current) setError("Could not load the agents. Please try again.");
    } finally {
      if (request === generation.current) setLoading(false);
    }
  }, [workspaceId, sessionId]);

  useEffect(() => {
    switching.current = false;
    setSaving(null);
    setSelection(null);
    setError(null);
    if (workspaceId && sessionId) void reload();
    else setLoading(false);
    return () => { ++generation.current; };
  }, [workspaceId, sessionId, reload]);

  async function choose(agent: string) {
    if (!workspaceId || !sessionId || switching.current || agent === selection?.current) return;
    switching.current = true;
    const request = ++generation.current;
    setSaving(agent);
    setError(null);
    try {
      const result = await setSessionAgent(workspaceId, sessionId, agent);
      if (request === generation.current) setSelection(result);
    } catch {
      if (request === generation.current) setError("Could not confirm the agent change. Reload agents to check the current selection.");
    } finally {
      if (request === generation.current) { switching.current = false; setSaving(null); setLoading(false); }
    }
  }

  return { selection, loading, saving, error, reload, choose };
}

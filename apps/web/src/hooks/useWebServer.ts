import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { disableWebServer, enableWebServer, getWebServer, setWebServerAgentAllowed, type WebServer } from "../api/webServerApi";

export { WEB_SERVER_NAME } from "../api/webServerApi";

/** Why a change was refused, in the user's terms. */
function reason(failure: unknown, name: string | null): string {
  if (failure instanceof ApiError) {
    if (failure.status === 409 && name) return `${name} is taken or reserved. Try another name, or restart the workspace if it was made before web servers.`;
    if (failure.status === 503) return "Start the workspace first: its web server runs inside it.";
  }
  return "Could not change the web server. Please try again.";
}

/** The workspace's public web server, as the API holds it. */
export function useWebServer(workspaceId: string) {
  const [server, setServer] = useState<WebServer | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    const current = ++generation.current;
    setServer(null);
    setLoading(true);
    setError(null);
    setSaving(false);
    getWebServer(workspaceId)
      .then((value) => { if (generation.current === current) setServer(value); })
      .catch(() => { if (generation.current === current) setError("Could not load the web server."); })
      .finally(() => { if (generation.current === current) setLoading(false); });
    return () => { generation.current++; };
  }, [workspaceId, attempt]);

  /**
   * Saves only what changed: the agent's permission first, which needs no running workspace, then
   * the server itself; null turns it off. Answers the problem to show, or null once it is saved.
   */
  async function update(name: string | null, agentAllowed: boolean): Promise<string | null> {
    const current = generation.current;
    setSaving(true);
    try {
      if (server && agentAllowed !== server.agentAllowed) {
        const saved = await setWebServerAgentAllowed(workspaceId, agentAllowed);
        if (generation.current === current) setServer(saved);
      }
      if (name === (server?.name ?? null)) return null;
      if (name === null) {
        await disableWebServer(workspaceId);
        if (generation.current === current) setServer((value) => value && { ...value, name: null, url: null });
      } else {
        const saved = await enableWebServer(workspaceId, name);
        if (generation.current === current) setServer(saved);
      }
      return null;
    } catch (failure) {
      return reason(failure, name);
    } finally {
      if (generation.current === current) setSaving(false);
    }
  }
  return { server, loading, saving, error, update, retry: () => setAttempt((value) => value + 1) };
}

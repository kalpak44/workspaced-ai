import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/http";
import { getWebDomains, saveWebDomain, removeWebDomain, type WebDomain } from "../api/webServerApi";
export { WEB_SERVER_NAME } from "../api/webServerApi";

export function useWebDomains(workspaceId: string, revision: string | null) {
  const [domains, setDomains] = useState<WebDomain[]>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    setLoading(true);
    getWebDomains(workspaceId).then(value => { if (current === generation.current) { setDomains(value); setError(""); } })
      .catch(() => { if (current === generation.current) setError("Could not load public addresses."); })
      .finally(() => { if (current === generation.current) setLoading(false); });
    return () => { generation.current++; };
  }, [workspaceId, revision, attempt]);
  async function change(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError("");
    try { await action(); }
    catch (failure) {
      setError(failure instanceof ApiError && failure.status === 409
        ? "This name is taken, reserved, or the workspace needs a restart. Try another name or restart it."
        : "Could not change this address. Check that the workspace is running and try again.");
    } finally { setBusy(false); }
  }

  async function save(name: string, enabled: boolean) {
    setDomains(await saveWebDomain(workspaceId, name, enabled));
  }
  async function remove(name: string) {
    await removeWebDomain(workspaceId, name);
    setDomains(value => value.filter(d => d.name !== name));
  }
  return { domains, busy, loading, error, setError, change, save, remove, retry: () => setAttempt(value => value + 1) };
}

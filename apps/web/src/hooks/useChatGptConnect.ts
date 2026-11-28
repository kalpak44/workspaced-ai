import { useEffect, useRef, useState } from "react";
import { cancelChatGpt, connectChatGpt, pollChatGpt, selectAiConnector, type ChatGptAttempt } from "../api/aiConnectorsApi";
import { ApiError } from "../api/http";

/** Runs one ChatGPT device sign-in at a time and stages the resulting profile. */
export function useChatGptConnect(workspaceId: string, onConnected: (connectorId: string) => Promise<boolean | void>) {
  const [attempt, setAttempt] = useState<ChatGptAttempt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const latest = useRef(onConnected);
  latest.current = onConnected;
  const pendingAttempt = useRef(attempt);
  pendingAttempt.current = attempt;
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => {
    active.current = false;
    if (pendingAttempt.current) void cancelChatGpt(workspaceId,pendingAttempt.current.id).catch(() => undefined);
  }; }, [workspaceId]);

  useEffect(() => {
    if (!attempt) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const read = async () => {
      if (Date.parse(attempt.expiresAt) <= Date.now()) {
        setAttempt(null); setError("ChatGPT sign-in expired. Please connect again."); return;
      }
      try {
        const result = await pollChatGpt(workspaceId, attempt.id);
        if (cancelled) return;
        setError("");
        if (result.status === "complete" && result.connectorId) {
          setBusy(true);
          try {
            await selectAiConnector(workspaceId, result.connectorId);
            if (cancelled) return;
            const restartRequired = await latest.current(result.connectorId);
            if (!cancelled) setNotice(restartRequired === false ? "ChatGPT connected. Your workspace is ready to chat." : "ChatGPT connected. Restart the workspace to activate this profile.");
          } catch {
            if (!cancelled) setError("ChatGPT was saved. Select it in Saved connector to finish applying this connection.");
          } finally { if (!cancelled) { setBusy(false); setAttempt(null); } }
          return;
        }
        if (result.status !== "pending") {
          setAttempt(null); setError("ChatGPT sign-in expired or was cancelled. Please connect again."); return;
        }
        timer = setTimeout(read, attempt.pollSeconds * 1000);
      } catch {
        if (cancelled) return;
        // A transient network error must not discard an authorization that may have completed.
        setError("Could not check ChatGPT sign-in. Retrying…");
        timer = setTimeout(read, attempt.pollSeconds * 1000);
      }
    };
    timer = setTimeout(read, attempt.pollSeconds * 1000);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [attempt, workspaceId]);

  const start = async (name: string, accepted: boolean) => {
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await connectChatGpt(workspaceId, name, accepted);
      if (active.current) setAttempt(result);
    } catch (cause) {
      if (active.current) setError(cause instanceof ApiError && cause.status === 409
        ? "Start the workspace and finish or cancel any pending ChatGPT sign-in before connecting again."
        : "Could not start ChatGPT sign-in. Please try again.");
    } finally { if (active.current) setBusy(false); }
  };
  const cancel = async () => {
    if (!attempt) return;
    setBusy(true); setError("");
    try { await cancelChatGpt(workspaceId, attempt.id); setAttempt(null); }
    catch { setError("Could not cancel sign-in. Please try again."); }
    finally { setBusy(false); }
  };
  return { attempt, busy, error, notice, start, cancel };
}

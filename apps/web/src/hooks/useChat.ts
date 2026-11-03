import { useEffect, useRef, useState } from "react";
import {
  abortSession,
  type ChatMessage,
  type ChatPart,
  listMessages,
  sendMessage,
  sessionEventsUrl,
  type SessionEvents,
} from "../api/sessionsApi";

export type { ChatMessage, ChatPart } from "../api/sessionsApi";

type Messages = readonly ChatMessage[];

// A prompt typed before its session's page was open. The page sends it once its stream is
// listening, so not a token of the reply is missed.
const queued = new Map<string, string>();

/** Hands `text` to the chat that opens `sessionId` next. */
export function queuePrompt(sessionId: string, text: string): void {
  queued.set(sessionId, text);
}

function upsertMessage(messages: Messages, id: string, role?: ChatMessage["role"]): ChatMessage[] {
  if (messages.some((m) => m.id === id)) {
    return messages.map((m) => (m.id === id && role ? { ...m, role } : m));
  }
  return [...messages, { id, role: role ?? "assistant", parts: [] }];
}

function updatePart(
  messages: Messages,
  messageId: string,
  partId: string,
  change: (part: ChatPart | undefined) => ChatPart,
): ChatMessage[] {
  return upsertMessage(messages, messageId).map((m) => {
    if (m.id !== messageId) {
      return m;
    }
    const hasPart = m.parts.some((p) => p.id === partId);
    const parts = hasPart
      ? m.parts.map((p) => (p.id === partId ? change(p) : p))
      : [...m.parts, change(undefined)];
    return { ...m, parts };
  });
}

/**
 * The history is authoritative for every message it holds; a message only the stream has
 * seen so far is newer than the history, so it is kept after it.
 */
function merge(streamed: Messages, history: Messages): ChatMessage[] {
  const known = new Set(history.map((m) => m.id));
  return [...history, ...streamed.filter((m) => !known.has(m.id))];
}

/**
 * One session's conversation: its history, then every change pushed over server-sent
 * events while it is open. `onTitle` hears when the agent names the session.
 */
export function useChat(
  workspaceId: string | null,
  sessionId: string | null,
  onTitle: (sessionId: string, title: string) => void,
) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [busy, setBusy] = useState(false);
  // Loading from the first render, so an empty conversation is never shown for a full one.
  const [loading, setLoading] = useState(workspaceId !== null && sessionId !== null);
  const [error, setError] = useState<string | null>(null);
  // Held in a ref so a new callback each render does not reopen the stream.
  const titled = useRef(onTitle);
  titled.current = onTitle;

  useEffect(() => {
    setMessages([]);
    setBusy(false);
    setError(null);
    if (workspaceId === null || sessionId === null) {
      return undefined;
    }
    let active = true;
    const load = () =>
      listMessages(workspaceId, sessionId).then(
        (history) => active && setMessages((current) => merge(current, history)),
        () => active && setError("Could not load the conversation"),
      );
    setLoading(true);
    void load().finally(() => active && setLoading(false));

    const source = new EventSource(sessionEventsUrl(workspaceId, sessionId));
    const on = <K extends keyof SessionEvents>(name: K, handle: (data: SessionEvents[K]) => void) =>
      source.addEventListener(name, (event) => handle(JSON.parse(event.data) as SessionEvents[K]));
    on("message", ({ id, role }) => setMessages((current) => upsertMessage(current, id, role)));
    on("part", ({ messageId, part }) =>
      setMessages((current) => updatePart(current, messageId, part.id, () => part)),
    );
    on("delta", ({ messageId, partId, delta }) =>
      setMessages((current) =>
        updatePart(current, messageId, partId, (part) => ({
          id: partId,
          type: "text",
          tool: null,
          status: null,
          ...part,
          text: (part?.text ?? "") + delta,
        })),
      ),
    );
    on("status", ({ busy: working }) => {
      setBusy(working);
      // A reply that finished is reconciled with the history, in case the stream opened
      // after it had started and missed its beginning.
      if (!working) {
        void load();
      }
    });
    on("title", ({ title }) => titled.current(sessionId, title));
    on("error", ({ message }) => setError(message));

    const prompt = queued.get(sessionId);
    if (prompt !== undefined) {
      queued.delete(sessionId);
      setBusy(true);
      sendMessage(workspaceId, sessionId, prompt).catch(() => {
        if (active) {
          setBusy(false);
          setError("Could not send the message");
        }
      });
    }
    return () => {
      active = false;
      source.close();
    };
  }, [workspaceId, sessionId]);

  /**
   * The reply, and the message itself, arrive over the stream; only the request's own
   * failure is reported here.
   */
  const send = (text: string): Promise<boolean> => {
    if (workspaceId === null || sessionId === null) {
      return Promise.resolve(false);
    }
    setBusy(true);
    setError(null);
    return sendMessage(workspaceId, sessionId, text).then(
      () => true,
      () => {
        setBusy(false);
        setError("Could not send the message");
        return false;
      },
    );
  };

  const stop = () => {
    if (workspaceId !== null && sessionId !== null) {
      abortSession(workspaceId, sessionId).catch(() => setError("Could not stop the reply"));
    }
  };

  return { messages, busy, loading, error, dismissError: () => setError(null), send, stop };
}

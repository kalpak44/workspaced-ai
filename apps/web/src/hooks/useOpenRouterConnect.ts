import { useEffect, useRef, useState } from "react";
import { authorizeOpenRouter, exchangeOpenRouterCode, type OpenRouterKeyOptions } from "../api/openRouterAuthApi";
import { OPENROUTER_CALLBACK_PATH, OPENROUTER_CHANNEL, type OpenRouterCallback } from "../lib/openRouterCallback";

export type OpenRouterConnectStatus = "idle" | "waiting" | "exchanging" | "connected" | "blocked" | "failed";

/**
 * Gets a key from the user's own OpenRouter account through OpenRouter's PKCE flow, in a popup so
 * the dialog that asked keeps its input. `onKey` receives the key once OpenRouter issues it.
 */
export function useOpenRouterConnect(onKey: (key: string) => void) {
  const [status, setStatus] = useState<OpenRouterConnectStatus>("idle");
  // Only the latest attempt's code is accepted; a retry makes every earlier one stale.
  const attempt = useRef<{ verifier: string; state: string } | null>(null);
  const keyHandler = useRef(onKey);

  useEffect(() => {
    keyHandler.current = onKey;
  }, [onKey]);

  useEffect(() => {
    const channel = new BroadcastChannel(OPENROUTER_CHANNEL);
    channel.onmessage = ({ data }: MessageEvent<OpenRouterCallback>) => {
      const current = attempt.current;
      // OpenRouter returns no state on a refusal, so a refused attempt simply stays open to retry.
      if (!current || !data.code || data.state !== current.state) return;
      attempt.current = null;
      setStatus("exchanging");
      exchangeOpenRouterCode(data.code, current.verifier).then(
        (key) => {
          setStatus("connected");
          keyHandler.current(key);
        },
        () => setStatus("failed"),
      );
    };
    return () => channel.close();
  }, []);

  /**
   * `keyLabel` names the key in the user's OpenRouter account; it may have to create something
   * first, and null gives up, closing the window.
   */
  const connect = (keyLabel: () => Promise<string | null>, options?: OpenRouterKeyOptions) => {
    // Opened before anything is awaited, or the browser no longer counts it as the user's click.
    const popup = globalThis.open("", "openrouter", "popup,width=520,height=720");
    if (!popup) {
      setStatus("blocked");
      return;
    }
    setStatus("waiting");
    keyLabel()
      .then((label) => {
        if (label === null) {
          popup.close();
          setStatus("idle");
          return;
        }
        return authorizeOpenRouter(`${globalThis.location.origin}${OPENROUTER_CALLBACK_PATH}`, label, options).then(
          ({ url, verifier, state }) => {
            attempt.current = { verifier, state };
            popup.location.href = url;
          },
        );
      })
      .catch(() => {
        popup.close();
        setStatus("failed");
      });
  };

  return { status, connect };
}

/** Where OpenRouter returns the user once they approve a key for us. */
export const OPENROUTER_CALLBACK_PATH = "/connect/openrouter";

// A channel rather than window.opener: OpenRouter's sign-in can sever the opener on the way back,
// and a channel reaches every window of this origin regardless.
export const OPENROUTER_CHANNEL = "workspaced:openrouter";

/** What the callback window hands the dialog: the code to exchange, or the user's refusal. */
export interface OpenRouterCallback {
  readonly code: string | null;
  readonly state: string | null;
}

/**
 * On the callback page, passes OpenRouter's code to the window that asked for it and closes,
 * instead of starting the app. Reports whether this was the callback page.
 */
export function relayOpenRouterCallback(location: Location = globalThis.location): boolean {
  if (location.pathname !== OPENROUTER_CALLBACK_PATH) {
    return false;
  }
  const params = new URLSearchParams(location.search);
  const message: OpenRouterCallback = { code: params.get("code"), state: params.get("state") };
  const channel = new BroadcastChannel(OPENROUTER_CHANNEL);
  channel.postMessage(message);
  channel.close();
  // The code is single-use and already sent on, so it does not stay in the address bar.
  globalThis.history.replaceState(null, "", OPENROUTER_CALLBACK_PATH);
  document.body.textContent = "OpenRouter is connected. You can close this window.";
  globalThis.close();
  return true;
}

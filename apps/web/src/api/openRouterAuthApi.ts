const AUTH_URL = "https://openrouter.ai/auth";
const KEYS_URL = "https://openrouter.ai/api/v1/auth/keys";

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCodePoint(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

function randomToken(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

/** One authorization: the URL to send the user to, and what proves the code came back to us. */
export interface OpenRouterAuthorization {
  readonly url: string;
  readonly verifier: string;
  readonly state: string;
}

export interface OpenRouterKeyOptions {
  readonly limit?: string;
  readonly expiration?: string;
}

/** A fresh PKCE verifier and state per attempt, so a code from an older one is refused. */
export async function authorizeOpenRouter(callbackUrl: string, keyLabel: string, options: OpenRouterKeyOptions = {}): Promise<OpenRouterAuthorization> {
  const verifier = randomToken();
  const state = randomToken();
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  const params = new URLSearchParams({
    callback_url: callbackUrl,
    code_challenge: base64url(new Uint8Array(digest)),
    code_challenge_method: "S256",
    key_label: keyLabel,
    state,
  });
  if (options.limit) {
    const amount = Number(options.limit);
    if (Number.isFinite(amount) && amount > 0 && amount < 1_000_000 && /^\d+(\.\d{1,2})?$/.test(options.limit)) params.set("limit", options.limit);
  }
  if (options.expiration && ["1-hour", "1-day", "7-days", "30-days", "90-days", "180-days", "1-year", "never"].includes(options.expiration)) params.set("expiration", options.expiration);
  return { url: `${AUTH_URL}?${params}`, verifier, state };
}

/**
 * Trades the callback's code for a key on the user's own OpenRouter account. It goes to
 * OpenRouter directly, never through our API, which receives the key only with the workspace.
 */
export async function exchangeOpenRouterCode(code: string, verifier: string): Promise<string> {
  const response = await fetch(KEYS_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, code_verifier: verifier, code_challenge_method: "S256" }),
  });
  if (!response.ok) {
    throw new Error(`OpenRouter answered ${response.status}`);
  }
  const { key } = (await response.json()) as { key?: unknown };
  if (typeof key !== "string" || !key) {
    throw new Error("OpenRouter answered without a key");
  }
  return key;
}

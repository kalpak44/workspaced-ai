export class ApiError extends Error {
  readonly status: number;
  /** The problem detail the API gave, when it gave one; two refusals can share a status. */
  readonly detail: string | null;
  /** The rest of the problem the API answered with, such as a 402's shortCents. */
  readonly problem: Readonly<Record<string, unknown>>;

  constructor(status: number, detail: string | null = null, problem: Readonly<Record<string, unknown>> = {}) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
    this.problem = problem;
  }
}

async function problemOf(response: Response): Promise<Readonly<Record<string, unknown>>> {
  try {
    const body: unknown = await response.json();
    return body !== null && typeof body === "object" ? body as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

/** Announced on any 401, so an expired sign-in returns the app to the sign-in page. */
export const UNAUTHORIZED = "workspaced:unauthorized";

// The API's CSRF token, fetched once and kept for the page's lifetime. It proves a change comes
// from this page and not from another site riding on the sign-in cookie.
let csrf: Promise<string> | null = null;

const TRANSIENT = new Set([408, 429, 500, 502, 503, 504]);
const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);
export interface RetryOptions { readonly retry?: "idempotent" }

function retryDelay(response: Response | undefined, attempt: number): number | null {
  const value = response?.headers.get("Retry-After");
  if (!value) return 100 * 2 ** attempt;
  const milliseconds = /^\d+(?:\.\d+)?$/.test(value) ? Number(value) * 1000 : Date.parse(value) - Date.now();
  return Number.isFinite(milliseconds) && milliseconds <= 2000 ? Math.max(0, milliseconds) : null;
}

async function invalidJsonBody(response: Response): Promise<boolean> {
  if (!response.ok || response.status === 204 || response.status === 205
      || !/application\/(?:[\w.+-]*\+)?json/i.test(response.headers.get("Content-Type") ?? "")) return false;
  try { await response.clone().json(); return false; } catch { return true; }
}

async function fetchWithRetry(url: string, init?: RequestInit, options?: RetryOptions): Promise<Response> {
  const repeatable = SAFE.has((init?.method ?? "GET").toUpperCase()) || options?.retry === "idempotent";
  for (let attempt = 0; ; attempt++) {
    let response: Response;
    try {
      init?.signal?.throwIfAborted();
      response = await fetch(url, init);
    } catch (error) {
      if (!repeatable || attempt >= 2 || init?.signal?.aborted) throw error;
      await new Promise(resolve => setTimeout(resolve, 100 * 2 ** attempt));
      continue;
    }
    const emptyJson = repeatable && await invalidJsonBody(response);
    if (repeatable && attempt < 2 && (TRANSIENT.has(response.status) || emptyJson)) {
      const delay = retryDelay(response, attempt);
      if (delay !== null) { await new Promise(resolve => setTimeout(resolve, delay)); continue; }
    }
    if (emptyJson) throw new ApiError(502, "The server returned an empty response or invalid JSON. Please try again.");
    return response;
  }
}

function csrfToken(): Promise<string> {
  csrf ??= fetchWithRetry("/api/v1/auth/csrf")
    .then((response) => {
      if (!response.ok) throw new ApiError(response.status);
      return response.json();
    })
    .then((body: { token: string }) => body.token)
    .catch((error: unknown) => {
      csrf = null;
      throw error;
    });
  return csrf;
}

async function request(url: string, init?: RequestInit, options?: RetryOptions): Promise<Response> {
  const change = !SAFE.has((init?.method ?? "GET").toUpperCase());
  return fetchWithRetry(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(change ? { "X-XSRF-TOKEN": await csrfToken() } : {}),
      ...init?.headers,
    },
  }, options);
}

export async function send(url: string, init?: RequestInit, options?: RetryOptions): Promise<Response> {
  let response = await request(url, init, options);
  // The token's cookie is gone, e.g. cleared with the browser's site data: fetch a fresh one once.
  if (response.status === 403 && init?.method && !SAFE.has(init.method.toUpperCase())) {
    csrf = null;
    response = await request(url, init, options);
  }
  if (!response.ok) {
    if (response.status === 401) {
      globalThis.dispatchEvent(new Event(UNAUTHORIZED));
    }
    const problem = await problemOf(response);
    throw new ApiError(response.status, typeof problem.detail === "string" ? problem.detail : null, problem);
  }
  return response;
}

/** Forgets the CSRF token; for tests. */
export function resetCsrfToken(): void {
  csrf = null;
}

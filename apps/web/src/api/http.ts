export class ApiError extends Error {
  readonly status: number;
  /** The problem detail the API gave, when it gave one; two refusals can share a status. */
  readonly detail: string | null;

  constructor(status: number, detail: string | null = null) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

async function problemDetail(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.json();
    const detail = (body as { detail?: unknown } | null)?.detail;
    return typeof detail === "string" ? detail : null;
  } catch {
    return null;
  }
}

/** Announced on any 401, so an expired sign-in returns the app to the sign-in page. */
export const UNAUTHORIZED = "workspaced:unauthorized";

// The API's CSRF token, fetched once and kept for the page's lifetime. It proves a change comes
// from this page and not from another site riding on the sign-in cookie.
let csrf: Promise<string> | null = null;

function csrfToken(): Promise<string> {
  csrf ??= fetch("/api/v1/auth/csrf")
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

async function request(url: string, init?: RequestInit): Promise<Response> {
  const change = init?.method !== undefined && init.method !== "GET";
  return fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(change ? { "X-XSRF-TOKEN": await csrfToken() } : {}),
      ...init?.headers,
    },
  });
}

export async function send(url: string, init?: RequestInit): Promise<Response> {
  let response = await request(url, init);
  // The token's cookie is gone, e.g. cleared with the browser's site data: fetch a fresh one once.
  if (response.status === 403 && init?.method && init.method !== "GET") {
    csrf = null;
    response = await request(url, init);
  }
  if (!response.ok) {
    if (response.status === 401) {
      globalThis.dispatchEvent(new Event(UNAUTHORIZED));
    }
    throw new ApiError(response.status, await problemDetail(response));
  }
  return response;
}

/** Forgets the CSRF token; for tests. */
export function resetCsrfToken(): void {
  csrf = null;
}

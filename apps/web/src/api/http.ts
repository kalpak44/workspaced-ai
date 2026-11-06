export class ApiError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Announced on any 401, so an expired sign-in returns the app to the sign-in page. */
export const UNAUTHORIZED = "workspaced:unauthorized";

/**
 * The API's CSRF token, which it keeps in a cookie this page can read. Echoing it in a header
 * proves a change comes from this page and not from another site riding on the sign-in cookie.
 */
function csrfToken(): string | undefined {
  const cookie = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return cookie ? decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) : undefined;
}

export async function send(url: string, init?: RequestInit): Promise<Response> {
  const token = init?.method && init.method !== "GET" ? csrfToken() : undefined;
  const response = await fetch(url, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { "X-XSRF-TOKEN": token } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    if (response.status === 401) {
      globalThis.dispatchEvent(new Event(UNAUTHORIZED));
    }
    throw new ApiError(response.status);
  }
  return response;
}

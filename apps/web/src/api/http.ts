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

export async function send(url: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    if (response.status === 401) {
      globalThis.dispatchEvent(new Event(UNAUTHORIZED));
    }
    throw new ApiError(response.status);
  }
  return response;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
  }
}

export async function send(url: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    throw new ApiError(response.status);
  }
  return response;
}

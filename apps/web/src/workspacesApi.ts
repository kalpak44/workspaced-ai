export interface Workspace {
  readonly id: string;
  readonly name: string;
}

const BASE = "/api/v1/workspaces";

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`API answered ${status}`);
    this.name = "ApiError";
    this.status = status;
  }
}

async function send(url: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    throw new ApiError(response.status);
  }
  return response;
}

export async function listWorkspaces(): Promise<Workspace[]> {
  return (await send(BASE)).json();
}

export async function createWorkspace(name: string): Promise<Workspace> {
  return (await send(BASE, { method: "POST", body: JSON.stringify({ name }) })).json();
}

export async function renameWorkspace(id: string, name: string): Promise<Workspace> {
  return (await send(`${BASE}/${id}`, { method: "PUT", body: JSON.stringify({ name }) })).json();
}

export async function deleteWorkspace(id: string): Promise<void> {
  await send(`${BASE}/${id}`, { method: "DELETE" });
}

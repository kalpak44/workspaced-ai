import { act } from "@testing-library/react";
import { vi } from "vitest";
import type { Secret } from "../src/api/secretsApi";
import type { Workspace, WorkspaceStatus } from "../src/api/workspacesApi";

export const OPENROUTER = {
  id: "openrouter",
  name: "OpenRouter",
  baseUrl: "https://openrouter.ai/api/v1",
  apiKeyVariable: "OPENROUTER_API_KEY",
};

/** The key the fake provider refuses; every other key is accepted. */
export const REJECTED_KEY = "sk-bad";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Stands in for the browser's EventSource; `emit` plays a server-sent status event. */
export class FakeEventSource {
  static readonly instances: FakeEventSource[] = [];
  readonly listeners = new Map<string, (event: MessageEvent) => void>();
  readonly url: string;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, listener);
  }

  close() {
    this.closed = true;
  }

  static latest(): FakeEventSource {
    const source = FakeEventSource.instances.at(-1);
    if (!source) throw new Error("no EventSource was opened");
    return source;
  }

  static emit(id: string, status: WorkspaceStatus) {
    const event = new MessageEvent("status", { data: JSON.stringify({ id, status }) });
    act(() => FakeEventSource.latest().listeners.get("status")?.(event));
  }

  static emitDeleted(id: string) {
    const event = new MessageEvent("deleted", { data: JSON.stringify({ id }) });
    act(() => FakeEventSource.latest().listeners.get("deleted")?.(event));
  }
}

/**
 * Stands in for the API: an in-memory workspace table behind the same routes, its secrets,
 * the provider list, a ready health probe and an event stream. `fail` makes every later
 * workspace call answer with that status; `createStatus` is the status a new workspace
 * starts in.
 */
export function installFakeApi(
  initial: Workspace[] = [
    { id: "starter", name: "Starter", modelProvider: "openrouter", status: "READY" },
  ],
) {
  const rows = [...initial];
  // Names only, as the API keeps them; the provider key is added on every list.
  const secrets = new Map<string, string[]>();
  const sent: { name: string; value: string }[] = [];
  const state = {
    fail: 0,
    nextId: 1,
    createStatus: "READY" as WorkspaceStatus,
    // When set, POST or DELETE waits on it, so a test can look at the UI mid-request.
    holdCreate: null as Promise<void> | null,
    holdDelete: null as Promise<void> | null,
    holdSecretRemove: null as Promise<void> | null,
  };
  FakeEventSource.instances.length = 0;

  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/actuator/health/readiness")) {
      return new Response(null, { status: 200 });
    }
    if (state.fail) {
      return new Response(null, { status: state.fail });
    }
    if (url.endsWith("/api/v1/model-providers")) {
      return json([OPENROUTER]);
    }
    const secretsRoute = /\/api\/v1\/workspaces\/([^/]+)\/secrets(?:\/(.+))?$/.exec(url);
    if (secretsRoute) {
      return secretsResponse(secretsRoute[1], secretsRoute[2], init);
    }
    const id = url.split("/api/v1/workspaces/")[1];
    const body = init?.body
      ? (JSON.parse(String(init.body)) as { name: string; modelProvider?: string; apiKey?: string })
      : undefined;
    const index = rows.findIndex((w) => w.id === id);
    switch (init?.method ?? "GET") {
      case "POST": {
        if (state.holdCreate) await state.holdCreate;
        if (body!.apiKey === REJECTED_KEY) return json({ status: 422 }, 422);
        const created = {
          id: `ws-${state.nextId++}`,
          name: body!.name,
          modelProvider: body!.modelProvider!,
          status: state.createStatus,
        };
        rows.push(created);
        return json(created, 201);
      }
      case "PUT":
        if (index < 0) return json({}, 404);
        rows[index] = { ...rows[index], name: body!.name };
        return json(rows[index]);
      case "DELETE":
        if (state.holdDelete) await state.holdDelete;
        if (index < 0) return json({}, 404);
        rows.splice(index, 1);
        return new Response(null, { status: 204 });
      default:
        return json(rows);
    }
  });

  async function secretsResponse(workspaceId: string, name: string | undefined, init?: RequestInit) {
    if (!rows.some((w) => w.id === workspaceId)) return json({}, 404);
    const names = secrets.get(workspaceId) ?? [];
    switch (init?.method ?? "GET") {
      case "POST": {
        const secret = JSON.parse(String(init!.body)) as { name: string; value: string };
        if (secret.name === OPENROUTER.apiKeyVariable || names.includes(secret.name)) {
          return json({}, 409);
        }
        secrets.set(workspaceId, [...names, secret.name]);
        sent.push(secret);
        return json({ name: secret.name, managed: false } satisfies Secret, 201);
      }
      case "DELETE":
        if (state.holdSecretRemove) await state.holdSecretRemove;
        if (!names.includes(name!)) return json({}, 404);
        secrets.set(
          workspaceId,
          names.filter((n) => n !== name),
        );
        return new Response(null, { status: 204 });
      default:
        return json([
          { name: OPENROUTER.apiKeyVariable, managed: true },
          ...names.map((n) => ({ name: n, managed: false })),
        ] satisfies Secret[]);
    }
  }

  vi.stubGlobal("fetch", fetchFn);
  vi.stubGlobal("EventSource", FakeEventSource);
  return { rows, secrets, sent, state, fetchFn };
}

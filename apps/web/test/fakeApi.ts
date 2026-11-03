import { act } from "@testing-library/react";
import { vi } from "vitest";
import type { Secret } from "../src/api/secretsApi";
import type { ChatMessage, Session, SessionEvents } from "../src/api/sessionsApi";
import type { McpServer, ModelCatalogue } from "../src/api/settingsApi";
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

  /** The newest stream whose URL ends with `suffix`; the workspace stream by default. */
  static latest(suffix = "/api/v1/workspaces/events"): FakeEventSource {
    const source = FakeEventSource.instances.findLast((s) => s.url.endsWith(suffix));
    if (!source) throw new Error(`no EventSource was opened for ${suffix}`);
    return source;
  }

  private dispatch(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data) });
    act(() => this.listeners.get(type)?.(event));
  }

  static emit(id: string, status: WorkspaceStatus) {
    FakeEventSource.latest().dispatch("status", { id, status });
  }

  static emitDeleted(id: string) {
    FakeEventSource.latest().dispatch("deleted", { id });
  }

  /** Plays one event on the newest open session stream. */
  static emitSession<K extends keyof SessionEvents>(type: K, data: SessionEvents[K]) {
    const source = FakeEventSource.instances.findLast((s) => s.url.endsWith("/events") && s.url.includes("/sessions/"));
    if (!source) throw new Error("no session stream was opened");
    source.dispatch(type, data);
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
    { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, status: "READY" },
  ],
) {
  const rows = [...initial];
  // Names only, as the API keeps them; the provider key is added on every list.
  const secrets = new Map<string, string[]>();
  const sent: { name: string; value: string }[] = [];
  const sessions = new Map<string, Session[]>();
  const messages = new Map<string, ChatMessage[]>();
  const prompts: { sessionId: string; text: string }[] = [];
  const aborted: string[] = [];
  const mcp = new Map<string, McpServer[]>();
  const catalogue: ModelCatalogue = {
    defaultModel: "anthropic/claude-sonnet-5.5",
    models: [
      { id: "anthropic/claude-haiku-5.5", name: "Claude Haiku 5.5", contextLength: 200000, inputCost: 1, outputCost: 5 },
      { id: "anthropic/claude-sonnet-5.5", name: "Claude Sonnet 5.5", contextLength: 1000000, inputCost: 2, outputCost: 10 },
      { id: "openai/gpt-free", name: "GPT Free", contextLength: null, inputCost: 0, outputCost: 0 },
    ],
  };
  const state = {
    fail: 0,
    nextId: 1,
    createStatus: "READY" as WorkspaceStatus,
    // When set, POST or DELETE waits on it, so a test can look at the UI mid-request.
    holdCreate: null as Promise<void> | null,
    holdDelete: null as Promise<void> | null,
    holdSecretRemove: null as Promise<void> | null,
    nextSession: 1,
    // Answers every session call with 503, as a workspace whose harness is down does.
    harnessDown: false,
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
    const settingsRoute = /\/api\/v1\/workspaces\/([^/]+)\/(models|model|mcp-servers)(?:\/(.+))?$/.exec(url);
    if (settingsRoute) {
      return settingsResponse(settingsRoute[1], settingsRoute[2], settingsRoute[3], init);
    }
    const sessionsRoute =
      /\/api\/v1\/workspaces\/([^/]+)\/sessions(?:\/([^/]+))?(?:\/(messages|abort))?$/.exec(url);
    if (sessionsRoute) {
      return sessionsResponse(sessionsRoute[1], sessionsRoute[2], sessionsRoute[3], init);
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
          model: null,
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

  function settingsResponse(workspaceId: string, kind: string, name: string | undefined, init?: RequestInit) {
    const index = rows.findIndex((w) => w.id === workspaceId);
    if (index < 0) return json({}, 404);
    const method = init?.method ?? "GET";
    if (kind === "models") return state.harnessDown ? json({}, 503) : json(catalogue);
    if (kind === "model") {
      const { model } = JSON.parse(String(init!.body)) as { model: string | null };
      rows[index] = { ...rows[index], model };
      return json({ model });
    }
    const servers = mcp.get(workspaceId) ?? [];
    if (method === "POST") {
      const server = JSON.parse(String(init!.body)) as { name: string; url: string; headers: Record<string, string> };
      if (servers.some((s) => s.name === server.name)) return json({}, 409);
      if (server.url.includes("invalid")) return json({}, 400);
      const added = { name: server.name, url: server.url, headers: Object.keys(server.headers), status: "connected" };
      mcp.set(workspaceId, [...servers, added]);
      return json(added, 201);
    }
    if (method === "DELETE") {
      mcp.set(
        workspaceId,
        servers.filter((s) => s.name !== name),
      );
      return new Response(null, { status: 204 });
    }
    return json(servers);
  }

  function sessionsResponse(
    workspaceId: string,
    sessionId: string | undefined,
    action: string | undefined,
    init?: RequestInit,
  ) {
    if (state.harnessDown) return json({}, 503);
    const list = sessions.get(workspaceId) ?? [];
    const method = init?.method ?? "GET";
    if (!sessionId) {
      if (method === "POST") {
        const id = `s-${state.nextSession++}`;
        const session: Session = {
          id,
          title: null,
          path: `/home/workspace/sessions/${id}`,
          createdAt: new Date().toISOString(),
        };
        sessions.set(workspaceId, [...list, session]);
        return json(session, 201);
      }
      return json(list);
    }
    if (!list.some((s) => s.id === sessionId)) return json({}, 404);
    if (action === "messages" && method === "POST") {
      prompts.push({ sessionId, text: (JSON.parse(String(init!.body)) as { text: string }).text });
      return new Response(null, { status: 202 });
    }
    if (action === "messages") return json(messages.get(sessionId) ?? []);
    if (action === "abort") {
      aborted.push(sessionId);
      return new Response(null, { status: 204 });
    }
    sessions.set(
      workspaceId,
      list.filter((s) => s.id !== sessionId),
    );
    return new Response(null, { status: 204 });
  }

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
  return { rows, secrets, sent, sessions, messages, prompts, aborted, mcp, catalogue, state, fetchFn };
}

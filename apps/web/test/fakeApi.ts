import { act } from "@testing-library/react";
import { vi } from "vitest";
import type { Account } from "../src/api/accountApi";
import type { Backup } from "../src/api/backupsApi";
import type { Secret } from "../src/api/secretsApi";
import type { TaskContent } from "../src/api/tasksApi";
import type { Variable } from "../src/api/variablesApi";
import type { User } from "../src/api/usersApi";
import type { AgentForm, FormAnswer, ChatMessage, Session, SessionEvents } from "../src/api/sessionsApi";
import type { McpServer, ModelCatalogue, NewMcpServer } from "../src/api/settingsApi";
import type { Workspace, WorkspaceStatus } from "../src/api/workspacesApi";
import type { TerminalSession } from "../src/api/terminalsApi";
import type { SessionAgent } from "../src/api/sessionAgentsApi";

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
    { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, status: "READY", restartRequired: false },
  ],
) {
  const rows = [...initial];
  // Names only, as the API keeps them; the provider key is added on every list.
  const secrets = new Map<string, string[]>();
  const sent: { name: string; value: string }[] = [];
  const sessions = new Map<string, Session[]>();
  const messages = new Map<string, ChatMessage[]>();
  const forms = new Map<string, AgentForm[]>();
  const answers: { sessionId: string; formId: string; answer?: FormAnswer }[] = [];
  const prompts: { sessionId: string; text: string }[] = [];
  const aborted: string[] = [];
  // Each workspace's instruction files, by name; filled on first use as the harness seeds them.
  const instructions = new Map<string, Map<string, string>>();
  const tasks = new Map<string, Map<string, TaskContent>>();
  const variables = new Map<string, Variable[]>();
  const sessionTtl = new Map<string, { sessionTtlEnabled: boolean; sessionTtlHours: number }>();
  // Each workspace's web server name; a name in `takenNames` belongs to some other workspace.
  const webServers = new Map<string, string>();
  const webServerAgents = new Set<string>();
  const takenNames = new Set<string>(["taken"]);
  const mcp = new Map<string, McpServer[]>();
  const terminals = new Map<string, TerminalSession[]>();
  const sessionAgents = new Map<string, string>();
  const agentOptions: SessionAgent[] = [
    { id: "build", name: "Build", description: "Implement changes", mode: "primary" },
    { id: "plan", name: "Plan", description: "Plan before implementation", mode: "primary" },
    { id: "writer", name: "Writer", description: "Write documentation", mode: "all" },
  ];
  // Every server POSTed, as sent, so a test can check what the UI built.
  const mcpPosted: NewMcpServer[] = [];
  // Every workspace id a restart was asked for.
  const restarted: string[] = [];
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
    olderHarness: false,
    nextId: 1,
    createStatus: "READY" as WorkspaceStatus,
    // When set, POST or DELETE waits on it, so a test can look at the UI mid-request.
    holdCreate: null as Promise<void> | null,
    holdDelete: null as Promise<void> | null,
    holdRestart: null as Promise<void> | null,
    failRestart: false,
    holdSecretRemove: null as Promise<void> | null,
    nextSession: 1,
    nextTerminal: 1,
    failTerminalDelete: false,
    failTerminalCreate: false,
    failAgentList: false,
    failAgentSwitch: false,
    holdAgentSwitch: null as Promise<void> | null,
    failEditAfterTruncate: false,
    failAnswer: false,
    // Answers every session call with 503, as a workspace whose harness is down does.
    harnessDown: false,
  };
  FakeEventSource.instances.length = 0;
  // admin/admin, signed in unless a test signs out first; `password` is what changes it.
  const auth = {
    signedIn: true,
    password: "admin",
    failProfile: false,
    account: { id: "admin-id", username: "admin", firstName: "", lastName: "", avatar: null } as Account,
  };
  // Everyone else who can sign in; the signed-in account is always listed first.
  const users = {
    others: [{ id: "grace-id", username: "grace", firstName: "Grace", lastName: "Hopper", avatar: null }] as User[],
    nextId: 1,
    failDelete: false,
    // Every list URL asked for, to see which pages and orders were fetched.
    listed: [] as string[],
  };

  // Every file in a session's folder, as "<session>/<path>"; `linked` is each path a link was made for.
  const sessionFiles = Object.assign(new Set<string>(), {
    linked: [] as string[],
    failUpload: 0,
    holdUpload: null as Promise<void> | null,
  });
  const backups = {
    list: [] as Backup[],
    nextId: 1,
    restored: [] as string[],
    failStart: 0,
    failRun: false,
    // Each uploaded part as "<backup>:<part>:<bytes>"; failPart answers a part with that status.
    parts: [] as string[],
    failPart: 0,
    // A workspace's restore as its status page reads it; absent means it is not restoring.
    restoreProgress: new Map<string, { phase: string; done: number; total: number; progress: number }>(),
  };

  // Spring's paging: `sort` is "field,direction", repeated, most significant first. The list
  // order stands in for creation order, oldest first, with the signed-in account the oldest.
  function usersResponse(url: string, init?: RequestInit): Response | null {
    const route = /\/api\/v1\/users(?:\/([^/?]+))?(?:\?(.*))?$/.exec(url);
    if (!route) return null;
    if (!auth.signedIn) return json({}, 401);
    const [, id, query] = route;
    if (init?.method === "POST") {
      const { username, firstName, lastName } = JSON.parse(String(init.body));
      if (username === auth.account.username || users.others.some((u) => u.username === username)) return json({}, 409);
      const user: User = { id: `user-${users.nextId++}`, username, firstName, lastName, avatar: null };
      users.others.push(user);
      return json(user, 201);
    }
    if (init?.method === "DELETE") {
      if (id === auth.account.id) return json({}, 409);
      if (users.failDelete) return json({}, 500);
      if (!users.others.some((u) => u.id === id)) return json({}, 404);
      users.others = users.others.filter((u) => u.id !== id);
      return new Response(null, { status: 204 });
    }
    users.listed.push(url);
    const params = new URLSearchParams(query);
    const page = Number(params.get("page") ?? 0);
    const size = Number(params.get("size") ?? 20);
    const all: User[] = [auth.account, ...users.others];
    const created = new Map(all.map((u, index) => [u.id, index]));
    const orders = params.getAll("sort").map((order) => order.split(","));
    const sorted = [...all].sort((a, b) => {
      for (const [field, direction] of orders) {
        const sign = direction === "desc" ? -1 : 1;
        const diff = field === "createdAt"
          ? created.get(a.id)! - created.get(b.id)!
          : String(a[field as keyof User]).localeCompare(String(b[field as keyof User]));
        if (diff !== 0) return sign * diff;
      }
      return 0;
    });
    return json({
      content: sorted.slice(page * size, (page + 1) * size),
      page: { size, number: page, totalElements: all.length, totalPages: Math.ceil(all.length / size) },
    });
  }

  function accountResponse(url: string, init?: RequestInit): Response | null {
    if (url.endsWith("/api/v1/auth/csrf")) {
      return json({ headerName: "X-XSRF-TOKEN", token: "csrf-token" });
    }
    if (url.endsWith("/api/v1/auth/login")) {
      const { username, password } = JSON.parse(String(init?.body));
      if (username !== auth.account.username || password !== auth.password) return json({}, 401);
      auth.signedIn = true;
      return json({ account: auth.account, token: "jwt", expiresAt: "2099-01-01T00:00:00Z" });
    }
    if (url.endsWith("/api/v1/auth/logout")) {
      auth.signedIn = false;
      return new Response(null, { status: 204 });
    }
    if (!/\/api\/v1\/account(\/|$)/.test(url)) return null;
    if (!auth.signedIn) return json({}, 401);
    if (url.endsWith("/account/profile")) {
      if (auth.failProfile) return json({}, 500);
      const { avatar, ...names } = JSON.parse(String(init?.body));
      auth.account = { ...auth.account, ...names, avatar: avatar || null };
    } else if (url.endsWith("/account/password")) {
      const { currentPassword, newPassword } = JSON.parse(String(init?.body));
      if (currentPassword !== auth.password) return json({}, 422);
      auth.password = newPassword;
      return new Response(null, { status: 204 });
    }
    return json(auth.account);
  }

  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/actuator/health/readiness")) {
      return new Response(null, { status: 200 });
    }
    const accountAnswer = accountResponse(url, init);
    if (accountAnswer) {
      return accountAnswer;
    }
    const usersAnswer = usersResponse(url, init);
    if (usersAnswer) {
      return usersAnswer;
    }
    if (state.fail) {
      return new Response(null, { status: state.fail });
    }
    if (url.endsWith("/api/v1/model-providers")) {
      return json([OPENROUTER]);
    }
    const backupAnswer = backupsResponse(url, init);
    if (backupAnswer) {
      return backupAnswer;
    }
    const instructionsRoute = /\/api\/v1\/workspaces\/([^/]+)\/instructions(?:\/([^/]+))?$/.exec(url);
    if (instructionsRoute) {
      const [, id, name] = instructionsRoute;
      if (!rows.some((workspace) => workspace.id === id)) return json({}, 404);
      if (state.harnessDown) return json({}, 503);
      // A workspace's first start seeds the harness image's default.
      const files = instructions.get(id) ?? new Map([["working-style", "- Keep answers short."]]);
      instructions.set(id, files);
      if (init?.method === "PUT") {
        files.set(name, JSON.parse(String(init.body)).content);
        return json({ name, content: files.get(name) });
      }
      if (init?.method === "DELETE") {
        if (!files.delete(name)) return json({}, 404);
        return new Response(null, { status: 204 });
      }
      return json([...files].sort(([a], [b]) => a.localeCompare(b)).map(([n, content]) => ({ name: n, content })));
    }
    const variablesRoute = /\/api\/v1\/workspaces\/([^/]+)\/variables$/.exec(url);
    if (variablesRoute) {
      const [, id] = variablesRoute;
      if (!rows.some((workspace) => workspace.id === id)) return json({}, 404);
      if (state.harnessDown) return json({}, 503);
      if (init?.method === "PUT") variables.set(id, JSON.parse(String(init.body)));
      return json(variables.get(id) ?? []);
    }
    const tasksRoute = /\/api\/v1\/workspaces\/([^/]+)\/tasks(?:\/([^/]+))?$/.exec(url);
    if (tasksRoute) {
      const [, id, name] = tasksRoute;
      if (!rows.some((workspace) => workspace.id === id)) return json({}, 404);
      if (state.harnessDown) return json({}, 503);
      const files = tasks.get(id) ?? new Map<string, TaskContent>();
      tasks.set(id, files);
      if (init?.method === "PUT") {
        files.set(name, JSON.parse(String(init.body)));
        return json({ name, ...files.get(name) });
      }
      if (init?.method === "DELETE") {
        if (!files.delete(name)) return json({}, 404);
        return new Response(null, { status: 204 });
      }
      return json([...files].sort(([a], [b]) => a.localeCompare(b)).map(([n, task]) => ({ name: n, ...task })));
    }
    const settingsRoute = /\/api\/v1\/workspaces\/([^/]+)\/(models|model|mcp-servers|session-ttl|web-server)(?:\/(.+))?$/.exec(url);
    const terminalRoute = /\/api\/v1\/workspaces\/([^/]+)\/terminals(?:\/([^/]+))?$/.exec(url);
    if (terminalRoute) {
      const [, workspaceId, terminalId] = terminalRoute;
      if (!rows.some((workspace) => workspace.id === workspaceId)) return json({}, 404);
      if (state.harnessDown) return json({}, 503);
      const list = terminals.get(workspaceId) ?? [];
      if (init?.method === "POST") {
        if (state.failTerminalCreate) return json({}, 503);
        const terminal: TerminalSession = { id: `pty_${state.nextTerminal++}`, title: JSON.parse(String(init.body)).title, command: "/bin/bash", cwd: "/home/workspace/project", status: "running", pid: 123, exitCode: null };
        terminals.set(workspaceId, [...list, terminal]);
        return json(terminal, 201);
      }
      if (init?.method === "DELETE") {
        if (state.failTerminalDelete) return json({}, 503);
        terminals.set(workspaceId, list.filter((terminal) => terminal.id !== terminalId));
        return new Response(null, { status: 204 });
      }
      return json(list);
    }
    if (settingsRoute) {
      return settingsResponse(settingsRoute[1], settingsRoute[2], settingsRoute[3], init);
    }
    const filesRoute = /\/api\/v1\/workspaces\/([^/]+)\/sessions\/([^/]+)\/files\/([^/]+)$/.exec(url);
    if (filesRoute) {
      const [, , sessionId, name] = filesRoute;
      if (name === "links") {
        const { path } = JSON.parse(String(init!.body)) as { path: string };
        if (!sessionFiles.has(`${sessionId}/${path}`)) return json({}, 404);
        sessionFiles.linked.push(path);
        return json({ url: `https://app/api/v1/files/token-${path}`, expiresAt: "2099-01-01T00:00:00Z" });
      }
      if (sessionFiles.failUpload) return json({}, sessionFiles.failUpload);
      if (sessionFiles.holdUpload) await sessionFiles.holdUpload;
      const decoded = decodeURIComponent(name);
      sessionFiles.add(`${sessionId}/uploads/${decoded}`);
      return json({ name: decoded, path: `uploads/${decoded}`, size: (init!.body as Blob).size }, 201);
    }
    const sessionsRoute =
      /\/api\/v1\/workspaces\/([^/]+)\/sessions(?:\/([^/]+))?(?:\/(messages(?:\/[^/]+)?|abort|agent|forms(?:\/[^/]+(?:\/reply)?)?))?$/.exec(url);
    if (sessionsRoute) {
      if (sessionsRoute[3] === "agent") {
        const [, workspaceId, sessionId] = sessionsRoute;
        if (!sessions.get(workspaceId)?.some((session) => session.id === sessionId)) return json({}, 404);
        if (state.harnessDown || state.failAgentList) return json({}, 503);
        if (init?.method === "PUT") {
          if (state.holdAgentSwitch) await state.holdAgentSwitch;
          if (state.failAgentSwitch) return json({}, 503);
          const agent = JSON.parse(String(init.body)).agent;
          if (!agentOptions.some((option) => option.id === agent)) return json({}, 400);
          sessionAgents.set(sessionId, agent);
        }
        return json({ current: sessionAgents.get(sessionId) ?? "build", agents: agentOptions });
      }
      return sessionsResponse(sessionsRoute[1], sessionsRoute[2], sessionsRoute[3], init);
    }
    const secretsRoute = /\/api\/v1\/workspaces\/([^/]+)\/secrets(?:\/(.+))?$/.exec(url);
    if (secretsRoute) {
      return secretsResponse(secretsRoute[1], secretsRoute[2], init);
    }
    const restartRoute = /\/api\/v1\/workspaces\/([^/]+)\/restart$/.exec(url);
    if (restartRoute) {
      if (state.holdRestart) await state.holdRestart;
      if (!rows.some((workspace) => workspace.id === restartRoute[1])) return json({}, 404);
      if (state.failRestart) return json({}, 503);
      restarted.push(restartRoute[1]);
      markRestartRequired(restartRoute[1], false);
      return new Response(null, { status: 202 });
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
          restartRequired: false,
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
    if (kind === "web-server") {
      if (name === "agent") {
        const { allowed } = JSON.parse(String(init?.body)) as { allowed: boolean };
        if (allowed) webServerAgents.add(workspaceId);
        else webServerAgents.delete(workspaceId);
      } else if (method !== "GET" && state.harnessDown) return json({}, 503);
      else if (method === "PUT") {
        const { name: wanted } = JSON.parse(String(init?.body)) as { name: string };
        if (takenNames.has(wanted)) return json({ detail: `the name ${wanted} is taken` }, 409);
        if (state.olderHarness) return json({ detail: "the workspace runs an older harness; restart it to update, then try again" }, 409);
        webServers.set(workspaceId, wanted);
      }
      if (method === "DELETE") {
        webServers.delete(workspaceId);
        return new Response(null, { status: 204 });
      }
      const current = webServers.get(workspaceId) ?? null;
      return json({ domain: "workspaced.test", name: current, url: current && `https://box-${current}.workspaced.test/`, agentAllowed: webServerAgents.has(workspaceId) });
    }
    if (kind === "session-ttl") {
      if (method === "PUT") sessionTtl.set(workspaceId, JSON.parse(String(init?.body)));
      return json(sessionTtl.get(workspaceId) ?? { sessionTtlEnabled: false, sessionTtlHours: 168 });
    }
    if (kind === "models") return state.harnessDown ? json({}, 503) : json(catalogue);
    if (kind === "model") {
      const { model } = JSON.parse(String(init!.body)) as { model: string | null };
      rows[index] = { ...rows[index], model };
      return json({ model });
    }
    const servers = mcp.get(workspaceId) ?? [];
    if (method === "POST") {
      const server = JSON.parse(String(init!.body)) as NewMcpServer;
      mcpPosted.push(server);
      if (servers.some((s) => s.name === server.name)) return json({}, 409);
      if ("url" in server && server.url.includes("invalid")) return json({}, 400);
      const added = { name: server.name, status: "connected" };
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
    if (action === "forms") return json(forms.get(sessionId) ?? []);
    if (action?.startsWith("forms/")) {
      if (state.failAnswer) return json({}, 503);
      const formId = action.split("/")[1];
      answers.push({ sessionId, formId, ...(method === "POST" ? JSON.parse(String(init?.body)) : {}) });
      forms.set(sessionId, (forms.get(sessionId) ?? []).filter((form) => form.id !== formId));
      return new Response(null, { status: 204 });
    }
    if (action?.startsWith("messages/") && method === "PUT") {
      const id = action.slice("messages/".length);
      const history = messages.get(sessionId) ?? [];
      const index = history.findIndex((m) => m.id === id && m.role === "user");
      if (index < 0) return json({}, 404);
      const text = (JSON.parse(String(init!.body)) as { text: string }).text;
      messages.set(sessionId, [...history.slice(0, index), { id: `edited-${id}`, role: "user", parts: [{ id: "edit-text", type: "text", text, tool: null, status: null }] }]);
      if (state.failEditAfterTruncate) {
        messages.set(sessionId, history.slice(0, index));
        return json({}, 503);
      }
      prompts.push({ sessionId, text });
      return new Response(null, { status: 202 });
    }
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

  // Each read of a running backup moves it on by half, so a test sees it run and then finish.
  function advance(index: number) {
    const backup = backups.list[index];
    if (backup.status !== "RUNNING" || backup.uploaded) return;
    const progress = Math.min(100, backup.progress + 50);
    backups.list[index] = progress === 100
      ? { ...backup, progress, status: backups.failRun ? "FAILED" : "COMPLETED", phase: "done", sizeBytes: 2_500_000, error: backups.failRun ? "disk full" : null }
      : { ...backup, progress, phase: "archiving" };
  }

  function backupsResponse(url: string, init?: RequestInit): Response | null {
    const method = init?.method ?? "GET";
    const start = /\/api\/v1\/workspaces\/([^/]+)\/backups$/.exec(url);
    if (start && method === "POST") {
      const workspace = rows.find((w) => w.id === start[1]);
      if (!workspace) return json({}, 404);
      if (backups.failStart) return json({}, backups.failStart);
      const backup = newBackup({ id: `backup-${backups.nextId++}`, workspaceId: workspace.id, workspaceName: workspace.name, status: "RUNNING", progress: 0, phase: "preparing", sizeBytes: null });
      backups.list.unshift(backup);
      return json(backup, 202);
    }
    const uploads = /\/api\/v1\/workspaces\/([^/]+)\/backups\/uploads$/.exec(url);
    if (uploads && method === "POST") {
      const workspace = rows.find((w) => w.id === uploads[1]);
      if (!workspace) return json({}, 404);
      const { sizeBytes } = JSON.parse(String(init!.body)) as { sizeBytes: number };
      const backup = newBackup({ id: `backup-${backups.nextId++}`, workspaceId: workspace.id, workspaceName: workspace.name, status: "RUNNING", progress: 0, phase: "uploading", sizeBytes, uploaded: true });
      backups.list.unshift(backup);
      return json(backup, 201);
    }
    const part = /\/api\/v1\/backups\/([^/]+)\/parts\/(\d+)$/.exec(url);
    if (part && method === "PUT") {
      if (!backups.list.some((b) => b.id === part[1])) return json({}, 404);
      if (backups.failPart) return json({}, backups.failPart);
      backups.parts.push(`${part[1]}:${part[2]}:${(init!.body as Blob).size}`);
      return new Response(null, { status: 204 });
    }
    const restoring = /\/api\/v1\/workspaces\/([^/]+)\/restore$/.exec(url);
    if (restoring) {
      const progress = backups.restoreProgress.get(restoring[1]);
      return progress ? json(progress) : new Response(null, { status: 204 });
    }
    const route = /\/api\/v1\/backups(?:\/([^/]+))?(?:\/(restore|workspaces|download|complete))?$/.exec(url);
    if (!route) return null;
    const [, id, action] = route;
    if (!id) {
      backups.list.forEach((_, i) => advance(i));
      return json(backups.list.map((b) => ({ ...b, workspaceExists: rows.some((w) => w.id === b.workspaceId) })));
    }
    const index = backups.list.findIndex((b) => b.id === id);
    if (index < 0) return json({}, 404);
    const backup = backups.list[index];
    if (action === "restore") {
      backups.restored.push(id);
      return new Response(null, { status: 202 });
    }
    if (action === "complete") {
      backups.list[index] = { ...backup, status: "COMPLETED", phase: "done", progress: 100 };
      return json({ ...backups.list[index], workspaceExists: true });
    }
    if (action === "download") return json({ url: `https://storage/${id}`, expiresAt: "2099-01-01T00:00:00Z" });
    if (action === "workspaces") {
      const body = JSON.parse(String(init!.body)) as { name: string; apiKey?: string };
      if (body.apiKey === REJECTED_KEY) return json({}, 422);
      if (!body.apiKey && !rows.some((w) => w.id === backup.workspaceId)) return json({}, 400);
      const created: Workspace = { id: `ws-${state.nextId++}`, name: body.name, modelProvider: "openrouter", model: backup.model, status: "INITIALIZING", restartRequired: false };
      rows.push(created);
      return json(created, 201);
    }
    if (method === "DELETE") {
      backups.list.splice(index, 1);
      return new Response(null, { status: 204 });
    }
    advance(index);
    return json({ ...backups.list[index], workspaceExists: rows.some((w) => w.id === backup.workspaceId) });
  }

  function markRestartRequired(workspaceId: string, restartRequired: boolean) {
    const index = rows.findIndex((w) => w.id === workspaceId);
    rows[index] = { ...rows[index], restartRequired };
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
        markRestartRequired(workspaceId, true);
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
        markRestartRequired(workspaceId, true);
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
  /** The calls the code under test made, without the CSRF token fetch `send` makes first. */
  const apiCalls = () => fetchFn.mock.calls.filter(([url]) => !String(url).endsWith("/api/v1/auth/csrf"));
  return { webServers, webServerAgents, sessionFiles, backups, instructions, tasks, variables, apiCalls, auth, users, restarted, sessionTtl, rows, secrets, sent, sessions, messages, forms, answers, prompts, aborted, mcp, mcpPosted, terminals, sessionAgents, agentOptions, catalogue, state, fetchFn };
}

/** A completed backup with nothing in it, for a test to override what it is about. */
export function newBackup(overrides: Partial<Backup> = {}): Backup {
  return {
    id: "backup-0", workspaceId: "starter", workspaceName: "Starter", workspaceExists: true,
    status: "COMPLETED", phase: "done", progress: 100, sizeBytes: 1_200_000, error: null,
    createdAt: "2026-11-12T10:00:00Z", completedAt: "2026-11-12T10:01:00Z",
    modelProvider: "openrouter", model: null, sessionTtlEnabled: false, sessionTtlHours: 168,
    secrets: [], variables: [], instructions: [], tasks: [], sessions: 0, uploaded: false,
    ...overrides,
  };
}

import { act } from "@testing-library/react";
import { vi } from "vitest";
import type { Account } from "../src/api/accountApi";
import type { DailySpend, TopUp, WorkspaceSpend } from "../src/api/billingApi";
import type { DnsRecord, WorkspaceDomain } from "../src/api/domainsApi";
import type { ApiNotification } from "../src/api/notificationsApi";
import type { FirewallRule } from "../src/api/firewallApi";
import type { Recipe, RecipeInput } from "../src/api/recipesApi";
import type { Secret } from "../src/api/secretsApi";
import type { TaskContent } from "../src/api/tasksApi";
import type { Variable } from "../src/api/variablesApi";
import type { MailSender, NewMail } from "../src/api/mailApi";
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

/** The plans the fake API offers, in the order shown: Starter, Builder and Pro. */
export const VM_SIZES = [
  { plan: "Starter", serverType: "cx23", isDefault: true, cores: 2, memoryGb: 4, diskGb: 40, cpuType: "shared", architecture: "x86", location: "nbg1", monthlyNet: 10.645833, monthlyGross: 10.645833, recommended: true, available: true },
  { plan: "Builder", serverType: "cx33", isDefault: false, cores: 4, memoryGb: 8, diskGb: 80, cpuType: "shared", architecture: "x86", location: "nbg1", monthlyNet: 16.729167, monthlyGross: 16.729167, recommended: true, available: true },
  { plan: "Pro", serverType: "cx43", isDefault: false, cores: 8, memoryGb: 16, diskGb: 160, cpuType: "shared", architecture: "x86", location: "nbg1", monthlyNet: 28.895833, monthlyGross: 28.895833, recommended: true, available: true },
];

/** What the fake API has told the user, newest first, three of them unread. */
export const NOTIFICATIONS: readonly ApiNotification[] = [
  { id: "n12", tone: "SUCCESS", kind: "WORKSPACE", title: "Workspace created", summary: "demo-box was created and is starting.", body: "Your workspace demo-box was created. Its VM is starting, and the agent is ready for a session in a few minutes.", createdAt: "2026-11-15T08:42:00Z", read: false, action: { label: "Open workspaces", path: "/" } },
  { id: "n11", tone: "WARNING", kind: "BILLING", title: "demo-box will be deleted in about 5 days", summary: "Your balance runs out in about 5 days. Add funds to keep demo-box.", body: "Your balance pays for your workspaces for about 5 days more. Once it cannot pay the next hour, demo-box is permanently deleted with its files, sessions and secrets.", createdAt: "2026-11-15T07:10:00Z", read: false, action: { label: "Add funds", path: "/billing" } },
  { id: "n9", tone: "SUCCESS", kind: "RECIPE", title: "New rating on your recipe", summary: "Someone rated “Deploy a Next.js app” 5 stars.", body: "Your recipe “Deploy a Next.js app” received a rating of 5 stars. It now averages 4.6 from 12 ratings.", createdAt: "2026-11-14T11:25:00Z", read: false, action: { label: "Open the store", path: "/store" } },
  { id: "n8", tone: "INFO", kind: "WORKSPACE", title: "Workspace deleted", summary: "sandbox was deleted.", body: "Your workspace sandbox was deleted with its VM, files, sessions and secrets. It no longer costs anything.", createdAt: "2026-11-13T02:01:00Z", read: true, action: null },
  { id: "n7", tone: "SUCCESS", kind: "BILLING", title: "Thank you for your payment", summary: "25.00 EUR was added to your balance.", body: "We received your payment and added 25.00 EUR to your balance. Its invoice is on the billing page.", createdAt: "2026-11-01T09:12:00Z", read: true, action: { label: "Go to billing", path: "/billing" } },
  { id: "n6", tone: "WARNING", kind: "BILLING", title: "Top-up not completed", summary: "A top-up of 10.00 EUR was never paid.", body: "The payment page for your top-up of 10.00 EUR closed before it was paid, so nothing was charged.", createdAt: "2026-10-30T12:00:00Z", read: true, action: { label: "Go to billing", path: "/billing" } },
  { id: "n4", tone: "WARNING", kind: "BILLING", title: "Payment failed", summary: "A top-up of 10.00 EUR did not go through.", body: "Your top-up of 10.00 EUR was declined and nothing was added to your balance.", createdAt: "2026-10-14T17:40:00Z", read: true, action: { label: "Go to billing", path: "/billing" } },
  { id: "n3", tone: "ERROR", kind: "WORKSPACE", title: "Workspace deleted", summary: "old-box was deleted: the balance ran out.", body: "Your balance could no longer pay for the next hour of old-box, so it was permanently deleted.", createdAt: "2026-10-09T15:30:00Z", read: true, action: { label: "Add funds", path: "/billing" } },
  { id: "n2", tone: "SUCCESS", kind: "RECIPE", title: "Recipe published", summary: "“Gmail drafts before sending” is now public.", body: "Your recipe “Gmail drafts before sending” is now public in the store, where anyone can apply and rate it.", createdAt: "2026-10-05T09:00:00Z", read: true, action: { label: "Open the store", path: "/store" } },
  { id: "n1", tone: "INFO", kind: "SYSTEM", title: "Welcome to workspaced", summary: "Add funds, then create a workspace to get your first agent.", body: "Welcome! A workspace is a sandbox with its own agent.", createdAt: "2026-10-01T08:00:00Z", read: true, action: { label: "Go to billing", path: "/billing" } },
];

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
    { id: "starter", name: "Starter", modelProvider: "openrouter", model: null, timeZone: "UTC", status: "READY", restartRequired: false },
  ],
) {
  const rows = [...initial];
  const connectors: { id: string; name: string; provider: string; trial?: boolean }[] = [];
  const connectorKeys = new Map<string, string>();
  const workspaceConnectors = new Map<string, string>();
  // Created without a key: unlisted until launched, as the API keeps them.
  const drafts = new Map<string, Omit<Workspace, "status">>();
  const launched: { id: string; apiKey: string }[] = [];
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
  // Each workspace's firewall; a port of "0" is refused the way the VM manager refuses one.
  const firewalls = new Map<string, { address: string | null; agentAllowed: boolean; rules: FirewallRule[] }>();
  // Each workspace's domains. The zone is the last two labels; connected.example stands in for a
  // DNS provider that applies the template itself.
  const domains = new Map<string, WorkspaceDomain[]>();
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
    // Each test's own copy, so one can sell a size out.
    vmSizes: VM_SIZES.map((size) => ({ ...size })),
    // When set, a create is refused as the balance not covering the required days of the size.
    noFunds: false,
    fail: 0,
    nextId: 1,
    createStatus: "READY" as WorkspaceStatus,
    // When set, POST or DELETE waits on it, so a test can look at the UI mid-request.
    trialRemaining: 1 as number | null,
    holdCreate: null as Promise<void> | null,
    holdDelete: null as Promise<void> | null,
    holdRestart: null as Promise<void> | null,
    failRestart: false,
    holdSecretRemove: null as Promise<void> | null,
    nextSession: 1,
    nextTerminal: 1,
    failTerminalDelete: false,
    // What a week of the demo's plan costs; keeping it needs that much balance.
    keepCostCents: 246,
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
  // Signed in unless a test signs out first; `resetRequests` counts the password reset emails asked for.
  const auth = {
    signedIn: true,
    resetRequests: 0,
    failProfile: false,
    failReset: false,
    failMail: false,
    failLogout: false,
    // What the next verification email resend answers, and how many were asked for.
    resendStatus: 204,
    resends: 0,
    account: { id: "admin-id", email: "admin@example.com", firstName: "", lastName: "", avatar: null, role: "ADMIN" } as Account,
  };
  // The store: every recipe, anyone's, and the drafts the agent left in each workspace.
  const recipes = {
    rows: [] as Recipe[],
    drafts: new Map<string, Map<string, string>>(),
    nextId: 1,
    failDelete: false,
    listed: [] as string[],
    /** Recipe ids sent with a new session, in order. */
    applied: [] as string[],
  };

  // The account's balance and top-ups; `nextStatus` is what Stripe reports on the next refresh.
  const billing = {
    balanceCents: 1240,
    reactivationFeeCents: 500,
    pausedForHours: 72,
    requiredHours: 168,
    card: null as string | null,
    autoTopUp: { enabled: false, belowCents: 1000, amountCents: 2000 },
    // The card a setup session returning with this id saved.
    cardSessions: new Map<string, string>(),
    savedAutoTopUps: [] as { enabled: boolean; belowCents: number; amountCents: number }[],
    monthlySpendCents: 0,
    fundedUntil: null as string | null,
    workspaces: [] as WorkspaceSpend[],
    usage: [] as DailySpend[],
    topUps: [
      { id: "t2", amountCents: 1000, status: "FAILED", createdAt: "2026-10-14T17:40:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false, automatic: false },
      { id: "t1", amountCents: 2500, status: "PAID", createdAt: "2026-10-02T08:05:00Z", paidAt: "2026-10-02T08:06:00Z", invoiceNumber: "WSAI-0001", invoiceAvailable: true, automatic: false },
    ] as TopUp[],
    started: [] as number[],
    refreshed: [] as string[],
    nextStatus: "PAID" as TopUp["status"],
    failStart: 0,
    vatPercent: 20 as number | undefined,
  };

  // The user's notifications; marking them read changes this copy only.
  const notifications = { rows: [...NOTIFICATIONS] };

  function notificationsResponse(url: string, init?: RequestInit): Response | null {
    if (url.endsWith("/api/v1/notifications")) {
      return json(notifications.rows);
    }
    if (url.endsWith("/api/v1/notifications/read") && init?.method === "PUT") {
      const { ids, read } = JSON.parse(String(init.body)) as { ids: string[]; read: boolean };
      notifications.rows = notifications.rows.map((n) => (ids.includes(n.id) ? { ...n, read } : n));
      return json(notifications.rows);
    }
    return null;
  }

  function billingResponse(url: string, init?: RequestInit): Response | null {
    if (url.endsWith("/api/v1/billing")) {
      const { balanceCents, vatPercent, reactivationFeeCents, pausedForHours, requiredHours, monthlySpendCents, fundedUntil, workspaces, usage, card, autoTopUp, topUps } = billing;
      return json({ balanceCents, currency: "eur", vatPercent, reactivationFeeCents, pausedForHours, requiredHours, monthlySpendCents, fundedUntil, workspaces, usage, card, autoTopUp, topUps });
    }
    if (url.endsWith("/api/v1/billing/card") && init?.method === "POST") {
      if (billing.failStart) return json({}, billing.failStart);
      return json({ checkoutUrl: "https://checkout.stripe.test/setup" }, 201);
    }
    if (url.endsWith("/api/v1/billing/card") && init?.method === "DELETE") {
      billing.card = null;
      billing.autoTopUp = { ...billing.autoTopUp, enabled: false };
      return new Response(null, { status: 204 });
    }
    if (url.endsWith("/api/v1/billing/card/confirm")) {
      const card = billing.cardSessions.get(JSON.parse(String(init?.body)).sessionId);
      if (!card) return json({}, 409);
      billing.card = card;
      return new Response(null, { status: 204 });
    }
    if (url.endsWith("/api/v1/billing/auto-top-up")) {
      const settings = JSON.parse(String(init?.body));
      if (settings.enabled && !billing.card) return json({}, 409);
      billing.savedAutoTopUps.push(settings);
      billing.autoTopUp = settings;
      return json(settings);
    }
    if (url.endsWith("/api/v1/billing/top-ups") && init?.method === "POST") {
      if (billing.failStart) return json({}, billing.failStart);
      const amountCents = JSON.parse(String(init.body)).amountCents as number;
      billing.started.push(amountCents);
      const id = `t${billing.topUps.length + 1}`;
      billing.topUps.unshift({ id, amountCents, status: "PENDING", createdAt: "2026-11-15T10:00:00Z", paidAt: null, invoiceNumber: null, invoiceAvailable: false, automatic: false });
      return json({ id, checkoutUrl: `https://checkout.stripe.test/${id}` }, 201);
    }
    const refresh = /\/api\/v1\/billing\/top-ups\/([^/]+)\/refresh$/.exec(url);
    if (refresh) {
      billing.refreshed.push(refresh[1]);
      const index = billing.topUps.findIndex((t) => t.id === refresh[1]);
      if (index < 0) return json({}, 404);
      const before = billing.topUps[index];
      if (before.status === "PENDING" && billing.nextStatus === "PAID") {
        billing.balanceCents += before.amountCents;
        billing.topUps[index] = { ...before, status: "PAID", paidAt: "2026-11-15T10:01:00Z", invoiceNumber: "WSAI-0002", invoiceAvailable: true };
      }
      return json(billing.topUps[index]);
    }
    return null;
  }

  function recipesResponse(url: string, init?: RequestInit): Response | null {
    const drafts = /\/api\/v1\/workspaces\/([^/]+)\/recipe-drafts(?:\/([^/]+))?$/.exec(url);
    if (drafts) {
      const [, id, name] = drafts;
      if (state.harnessDown) return json({}, 503);
      const files = recipes.drafts.get(id) ?? new Map<string, string>();
      recipes.drafts.set(id, files);
      if (init?.method === "PUT") {
        files.set(name, JSON.parse(String(init.body)).content);
        return json({ name, content: files.get(name) });
      }
      if (init?.method === "DELETE") {
        return files.delete(name) ? new Response(null, { status: 204 }) : json({}, 404);
      }
      return json([...files].sort(([a], [b]) => a.localeCompare(b)).map(([n, content]) => ({ name: n, content })));
    }
    const route = /\/api\/v1\/recipes(?:\/([^/?]+))?(\/rating)?(?:\?(.*))?$/.exec(url);
    if (!route) return null;
    const [, id, rating, query] = route;
    const visible = (recipe: Recipe) => recipe.visibility === "PUBLIC" || recipe.author.id === auth.account.id;
    const mine = (recipe: Recipe) => ({ ...recipe, mine: recipe.author.id === auth.account.id });
    if (!id && init?.method === "POST") {
      const input = JSON.parse(String(init.body)) as RecipeInput;
      const now = new Date().toISOString();
      const recipe: Recipe = {
        ...input,
        id: `recipe-${recipes.nextId++}`,
        author: { id: auth.account.id, firstName: auth.account.firstName, lastName: auth.account.lastName },
        createdAt: now,
        publishedAt: input.visibility === "PUBLIC" ? now : null,
        rating: { average: 0, count: 0 },
        myRating: null,
        uses: 0,
        mine: true,
      };
      recipes.rows.push(recipe);
      return json(recipe, 201);
    }
    if (!id) {
      recipes.listed.push(url);
      const params = new URLSearchParams(query);
      const scope = params.get("scope");
      const q = params.get("q")?.toLowerCase();
      const content = recipes.rows
        .filter((recipe) => (scope === "private" ? recipe.visibility === "PRIVATE" && recipe.author.id === auth.account.id : recipe.visibility === "PUBLIC"))
        .filter((recipe) => !params.get("category") || recipe.category === params.get("category"))
        .filter((recipe) => !q || recipe.title.toLowerCase().includes(q) || recipe.tags.some((tag) => tag.includes(q)))
        .map(({ content: _, ...summary }) => mine(summary as Recipe));
      return json({ content, page: { size: 24, number: 0, totalElements: content.length, totalPages: 1 } });
    }
    const index = recipes.rows.findIndex((recipe) => recipe.id === id && visible(recipe));
    if (index < 0) return json({}, 404);
    const recipe = recipes.rows[index];
    if (rating) {
      if (recipe.author.id === auth.account.id) return json({}, 409);
      const stars = init?.method === "DELETE" ? null : (JSON.parse(String(init?.body)).stars as number);
      recipes.rows[index] = { ...recipe, myRating: stars, rating: stars === null ? { average: 0, count: 0 } : { average: stars, count: 1 } };
      return stars === null ? new Response(null, { status: 204 }) : json({ ...recipes.rows[index].rating, myRating: stars });
    }
    // A recipe is final once created: only deleting it changes anything.
    if (init?.method === "PUT") return json({}, 405);
    if (init?.method === "DELETE") {
      if (recipe.author.id !== auth.account.id && auth.account.role !== "ADMIN") return json({}, 403);
      if (recipes.failDelete) return json({}, 500);
      recipes.rows.splice(index, 1);
      return new Response(null, { status: 204 });
    }
    return json(mine(recipes.rows[index]));
  }

  // Every message sent, as sent; `refuse` answers the next send as Mailjet refusing it.
  // `previewed` is every preview asked for, and each preview echoes what it was given.
  const mail = {
    configured: true,
    sent: [] as NewMail[],
    previewed: [] as unknown[],
    refuse: null as string | null,
    senders: ["contact", "support", "noreply"].map((localPart) => ({
      localPart,
      address: `${localPart}@workspaced.online`,
      name: "workspaced",
      signature: `Best regards,\n${localPart}`,
      footer: `You received this from ${localPart}@workspaced.online.`,
    })) as MailSender[],
  };

  function mailResponse(url: string, init?: RequestInit): Response | null {
    if (url.endsWith("/api/v1/mail") && !init?.method) {
      return json({ domain: "workspaced.online", noReply: "noreply@workspaced.online", configured: mail.configured, senders: mail.senders });
    }
    if (url.endsWith("/api/v1/mail/messages") && init?.method === "POST") {
      if (mail.refuse) {
        const detail = mail.refuse;
        mail.refuse = null;
        return new Response(JSON.stringify({ detail }), { status: 502 });
      }
      mail.sent.push(JSON.parse(String(init.body)));
      return json({ id: String(mail.sent.length) });
    }
    if (url.endsWith("/api/v1/mail/preview") && init?.method === "POST") {
      const request = JSON.parse(String(init.body));
      mail.previewed.push(request);
      return json({ html: `<p>${request.text}</p><p>${request.template.signature}</p><p>${request.template.footer}</p>` });
    }
    const sender = /\/api\/v1\/mail\/senders\/([^/]+)$/.exec(url);
    if (sender && init?.method === "PUT") {
      const index = mail.senders.findIndex((s) => s.localPart === sender[1]);
      if (index < 0) return json({}, 404);
      mail.senders[index] = { ...mail.senders[index], ...JSON.parse(String(init.body)) };
      return json(mail.senders[index]);
    }
    return null;
  }

  // Every file in a session's folder, as "<session>/<path>"; `linked` is each path a link was made for.
  const sessionFiles = Object.assign(new Set<string>(), {
    linked: [] as string[],
    failUpload: 0,
    holdUpload: null as Promise<void> | null,
  });

  function accountResponse(url: string, init?: RequestInit): Response | null {
    if (url.endsWith("/api/v1/auth/csrf")) {
      return json({ headerName: "X-XSRF-TOKEN", token: "csrf-token" });
    }
    if (url.endsWith("/api/v1/auth/logout")) {
      if (auth.failLogout) return json({}, 500);
      auth.signedIn = false;
      return new Response(null, { status: 204 });
    }
    if (url.endsWith("/api/v1/auth/verification-email") && init?.method === "POST") {
      auth.resends++;
      return auth.resendStatus === 204 ? new Response(null, { status: 204 }) : json({}, auth.resendStatus);
    }
    if (!/\/api\/v1\/account(\/|$)/.test(url)) return null;
    if (!auth.signedIn) return json({}, 401);
    if (url.endsWith("/account/profile")) {
      if (auth.failProfile) return json({}, 500);
      const { firstName, lastName } = JSON.parse(String(init?.body));
      auth.account = { ...auth.account, firstName, lastName };
    } else if (url.endsWith("/account/mail-preferences")) {
      if (auth.failMail) return json({}, 500);
      // As the API does: a preference left out keeps its value.
      auth.account = { ...auth.account, mail: { balanceReminders: true, tips: true, ...auth.account.mail, ...JSON.parse(String(init?.body)) } };
    } else if (url.endsWith("/account/tour")) {
      auth.account = { ...auth.account, tour: init?.method === "PUT" ? JSON.parse(String(init.body)) : null };
      return new Response(null, { status: 204 });
    } else if (url.endsWith("/account/password-reset")) {
      if (auth.failReset) return json({}, 500);
      auth.resetRequests++;
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
    const mailAnswer = mailResponse(url, init);
    if (mailAnswer) {
      return mailAnswer;
    }
    const recipesAnswer = recipesResponse(url, init);
    if (recipesAnswer) {
      return recipesAnswer;
    }
    const notificationsAnswer = notificationsResponse(url, init);
    if (notificationsAnswer) {
      return notificationsAnswer;
    }
    const billingAnswer = billingResponse(url, init);
    if (billingAnswer) {
      return billingAnswer;
    }
    if (state.fail) {
      return new Response(null, { status: state.fail });
    }
    const noticeRoute = /\/api\/v1\/ai-connectors\/([^/]+)\/acknowledgement$/.exec(url);
    if (noticeRoute) return new Response(null, { status: connectors.some(connector => connector.id === noticeRoute[1]) ? 204 : 404 });
    const connectorMetricsRoute = /\/api\/v1\/ai-connectors\/([^/]+)\/metrics$/.exec(url);
    if (connectorMetricsRoute) {
      if (!connectors.some(connector => connector.id === connectorMetricsRoute[1])) return json({}, 404);
      if (connectors.find(connector => connector.id === connectorMetricsRoute[1])?.trial) return json({ usage: state.trialRemaining === null ? null : 1 - state.trialRemaining, usageDaily: 0, usageWeekly: 0, usageMonthly: 0, limit: 1, limitRemaining: state.trialRemaining, limitReset: null, expiresAt: null, includeByokInLimit: true, byokUsage: 0, freeTier: false, freeRequestsLimit: null, freeRequestsRemaining: null });
      return json({ usage: connectorMetricsRoute[1] === "connector-2" ? 7 : 2.5, usageDaily: 0.5, usageWeekly: 1.5, usageMonthly: 2.5, limit: 10, limitRemaining: 7.5, limitReset: "monthly", expiresAt: null, includeByokInLimit: false, byokUsage: 0, freeTier: false, freeRequestsLimit: 50, freeRequestsRemaining: 40 });
    }
    const connectorRoute = /\/api\/v1\/ai-connectors(?:\/([^/]+))?$/.exec(url);
    if (connectorRoute) {
      if (init?.method === "POST") {
        const input = JSON.parse(String(init.body));
        if (input.providerNoticeAccepted !== true) return json({}, 400);
        if (input.apiKey === REJECTED_KEY) return json({}, 422);
        const connector = { id: `connector-${connectors.length + 1}`, name: input.name, provider: input.provider };
        connectors.push(connector); connectorKeys.set(connector.id, input.apiKey);
        return json(connector, 201);
      }
      if (init?.method === "DELETE") {
        const index = connectors.findIndex(c => c.id === connectorRoute[1]);
        if (index < 0) return json({}, 404);
        if (connectors[index].trial) return json({}, 409);
        const trial = connectors.find(connector => connector.trial);
        const affected = [...workspaceConnectors].filter(([, connectorId]) => connectorId === connectorRoute[1]);
        if (affected.length && !trial) return json({}, 503);
        for (const [workspaceId] of affected) { workspaceConnectors.set(workspaceId, trial!.id); markRestartRequired(workspaceId, true); }
        connectors.splice(index, 1); return new Response(null, { status: 204 });
      }
      return json(connectors);
    }
    const selectionRoute = /\/api\/v1\/workspaces\/([^/]+)\/ai-connector$/.exec(url);
    if (selectionRoute) {
      const id = selectionRoute[1];
      if (init?.method === "PUT") {
        const connectorId = JSON.parse(String(init.body)).connectorId;
        const connector = connectors.find(c => c.id === connectorId);
        if (!connector) return json({}, 404);
        workspaceConnectors.set(id, connectorId); markRestartRequired(id, true); return json(connector);
      }
      const current = connectors.find(c => c.id === workspaceConnectors.get(id));
      return current ? json(current) : new Response(null, { status: 204 });
    }
    if (url.endsWith("/api/v1/model-providers")) {
      return json([OPENROUTER]);
    }
    if (url.endsWith("/api/v1/vm-sizes")) {
      return json(state.vmSizes);
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
    const settingsRoute = /\/api\/v1\/workspaces\/([^/]+)\/(models|model|mcp-servers|session-ttl)(?:\/(.+))?$/.exec(url);
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
    const domainRoute = /\/api\/v1\/workspaces\/([^/]+)\/domains(?:\/([^/]+))?$/.exec(url);
    if (domainRoute) {
      const [, workspaceId, domainId] = domainRoute;
      if (!rows.some((workspace) => workspace.id === workspaceId)) return json({}, 404);
      if (state.fail) return json({}, state.fail);
      const list = domains.get(workspaceId) ?? [];
      domains.set(workspaceId, list);
      if (init?.method === "DELETE") {
        const gone = list.find((d) => d.id === decodeURIComponent(domainId ?? ""));
        if (!gone) return json({}, 404);
        domains.set(workspaceId, list.filter((d) => d !== gone));
        return json(gone.records);
      }
      if (init?.method === "POST") {
        const name = String(JSON.parse(String(init.body)).name).trim().toLowerCase();
        if (!/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(name)) {
          return json({ detail: "enter a domain such as example.com, or a name under one such as app.example.com" }, 400);
        }
        const labels = name.split(".");
        const domain = labels.slice(-2).join(".");
        const host = labels.length > 2 ? labels.slice(0, -2).join(".") : "@";
        const target = "vm-1.vm.workspaced.online";
        const records: DnsRecord[] = host === "@"
          ? [{ type: "A", host: "@", name, value: "203.0.113.10" }, { type: "AAAA", host: "@", name, value: "2001:db8::1" },
            { type: "CNAME", host: "www", name: `www.${name}`, value: target }]
          : [{ type: "CNAME", host, name, value: target }];
        const connect = domain === "connected.example";
        const added: WorkspaceDomain = {
          id: `domain-${list.length + 1}-${name}`, name, domain, host, mode: connect ? "DOMAIN_CONNECT" : "MANUAL", status: "PENDING",
          providerName: connect ? "GoDaddy" : null, connectUrl: connect ? `https://dcc.godaddy.example/apply?domain=${domain}` : null,
          records, createdAt: "2026-11-16T10:00:00Z", verifiedAt: null,
        };
        list.push(added);
        return json(added, 201);
      }
      return json(list);
    }
    const firewallRoute = /\/api\/v1\/workspaces\/([^/]+)\/firewall(\/agent)?$/.exec(url);
    if (firewallRoute) {
      const [, workspaceId, agent] = firewallRoute;
      if (!rows.some((workspace) => workspace.id === workspaceId)) return json({}, 404);
      if (state.fail) return json({}, state.fail);
      const firewall = firewalls.get(workspaceId) ?? { address: "203.0.113.10", agentAllowed: true, rules: [] };
      firewalls.set(workspaceId, firewall);
      if (agent) {
        firewall.agentAllowed = Boolean(JSON.parse(String(init?.body)).allowed);
        return new Response(null, { status: 204 });
      }
      if (init?.method === "PUT") {
        const { rules } = JSON.parse(String(init.body)) as { rules: FirewallRule[] };
        if (rules.some((rule) => rule.port === "0")) return json({ detail: "ports run from 1 to 65535" }, 400);
        firewall.rules = rules;
      }
      return json(firewall);
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
      /\/api\/v1\/workspaces\/([^/]+)\/sessions(?:\/([^/]+))?(?:\/(messages(?:\/[^/]+)?|abort|agent|forms(?:\/[^/]+(?:\/reply)?)?))?(?:\?(.*))?$/.exec(url);
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
      return sessionsResponse(sessionsRoute[1], sessionsRoute[2], sessionsRoute[3], init, sessionsRoute[4]);
    }
    const metricsRoute = /\/api\/v1\/workspaces\/([^/]+)\/metrics\?range=([\w]+)$/.exec(url);
    if (metricsRoute) {
      if (!rows.some((workspace) => workspace.id === metricsRoute[1])) return json({}, 404);
      return json(metricsResponse(metricsRoute[2]));
    }
    const secretsRoute = /\/api\/v1\/workspaces\/([^/]+)\/secrets(?:\/(.+))?$/.exec(url);
    if (secretsRoute) {
      return secretsResponse(secretsRoute[1], secretsRoute[2], init);
    }
    const timeZoneRoute = /\/api\/v1\/workspaces\/([^/]+)\/time-zone$/.exec(url);
    if (timeZoneRoute) {
      const index = rows.findIndex((workspace) => workspace.id === timeZoneRoute[1]);
      if (index < 0) return json({}, 404);
      if (state.fail) return json({}, state.fail);
      const { timeZone } = JSON.parse(String(init?.body)) as { timeZone: string };
      if (timeZone !== rows[index].timeZone) rows[index] = { ...rows[index], timeZone, restartRequired: true };
      return json(rows[index]);
    }
    const keepRoute = /\/api\/v1\/workspaces\/([^/]+)\/keep$/.exec(url);
    if (keepRoute && init?.method === "POST") {
      const index = rows.findIndex((item) => item.id === keepRoute[1]);
      if (index < 0) return json({}, 404);
      if (!rows[index].demo) return json({ detail: "only a running demo can be kept" }, 409);
      if (billing.balanceCents < state.keepCostCents) {
        return json({ detail: "add funds to keep it", shortCents: state.keepCostCents - billing.balanceCents }, 402);
      }
      rows[index] = { ...rows[index], demo: false, expiresAt: null };
      return json(rows[index]);
    }
    const tutorialRoute = /\/api\/v1\/workspaces\/([^/]+)\/tutorial-completed$/.exec(url);
    if (tutorialRoute && init?.method === "POST") {
      const workspace = rows.find((item) => item.id === tutorialRoute[1]);
      if (!workspace) return json({}, 404);
      if (state.fail) return json({}, state.fail);
      if (!workspace.demo || !workspace.expiresAt) return json({}, 409);
      if (!notifications.rows.some((item) => item.id === "tutorial-completed")) {
        notifications.rows.unshift({ id: "tutorial-completed", kind: "WORKSPACE", tone: "SUCCESS",
          title: "Tutorial completed", summary: `${workspace.name} will be deleted at ${workspace.expiresAt}.`,
          body: "Your trial will be permanently deleted. Save anything you want to keep.",
          createdAt: new Date().toISOString(), read: false,
          action: { label: "Open workspace", path: `/workspaces/${workspace.id}` } });
      }
      return new Response(null, { status: 204 });
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
    const launchRoute = /\/api\/v1\/workspaces\/([^/]+)\/launch$/.exec(url);
    if (launchRoute) {
      const draft = drafts.get(launchRoute[1]);
      if (!draft) return json({}, 404);
      const input = JSON.parse(String(init!.body)) as { apiKey?: string; connectorId?: string };
      const apiKey = input.connectorId ? (connectorKeys.get(input.connectorId) ?? "trial-key") : input.apiKey!;
      if (input.connectorId) workspaceConnectors.set(draft.id, input.connectorId);
      if (apiKey === REJECTED_KEY) return json({ status: 422 }, 422);
      drafts.delete(draft.id);
      launched.push({ id: draft.id, apiKey });
      const workspace = { ...draft, status: state.createStatus, ...demoFields(draft.demo === true) };
      rows.push(workspace);
      return json(workspace);
    }
    const id = url.split("/api/v1/workspaces/")[1];
    const body = init?.body
      ? (JSON.parse(String(init.body)) as { name: string; modelProvider?: string; apiKey?: string; connectorId?: string; timeZone?: string; serverType?: string; demo?: boolean })
      : undefined;
    const index = rows.findIndex((w) => w.id === id);
    switch (init?.method ?? "GET") {
      case "POST": {
        if (state.holdCreate) await state.holdCreate;
        if (body!.apiKey === REJECTED_KEY) return json({ status: 422 }, 422);
        if (state.vmSizes.some((size) => size.serverType === body!.serverType && !size.available)) return json({ status: 409 }, 409);
        if (body!.demo && !auth.account.demoAvailable) return json({ status: 409 }, 409);
        if (state.noFunds && !body!.demo) return json({ status: 402, detail: "add funds" }, 402);
        if (body!.apiKey === undefined && !body!.connectorId) {
          const draft = {
            id: `ws-${state.nextId++}`,
            name: body!.name,
            modelProvider: body!.modelProvider!,
            model: null,
            timeZone: body!.timeZone ?? "UTC",
            restartRequired: false,
            demo: body!.demo === true,
          };
          drafts.set(draft.id, draft);
          return json({ ...draft, status: "DRAFT" }, 201);
        }
        const created = {
          id: `ws-${state.nextId++}`,
          name: body!.name,
          modelProvider: body!.modelProvider!,
          model: null,
          timeZone: body!.timeZone ?? "UTC",
          status: state.createStatus,
          restartRequired: false,
          ...demoFields(body!.demo === true),
        };
        rows.push(created);
        if (body!.connectorId) workspaceConnectors.set(created.id, body!.connectorId);
        return json(created, 201);
      }
      case "PUT":
        if (index < 0) return json({}, 404);
        rows[index] = { ...rows[index], name: body!.name };
        return json(rows[index]);
      case "DELETE":
        if (state.holdDelete) await state.holdDelete;
        if (drafts.delete(id)) return new Response(null, { status: 204 });
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
    query?: string,
  ) {
    if (state.harnessDown) return json({}, 503);
    const list = sessions.get(workspaceId) ?? [];
    const method = init?.method ?? "GET";
    if (!sessionId) {
      if (method === "POST") {
        const recipe = new URLSearchParams(query).get("recipe");
        if (recipe) recipes.applied.push(recipe);
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

  // A demo is deleted four hours after it starts, and it uses up the account's one demo.
  function demoFields(demo: boolean) {
    if (!demo) return {};
    auth.account = { ...auth.account, demoAvailable: false };
    return { demo: true, expiresAt: new Date(Date.now() + 4 * 3600_000).toISOString() };
  }

  function markRestartRequired(workspaceId: string, restartRequired: boolean) {
    const index = rows.findIndex((w) => w.id === workspaceId);
    rows[index] = { ...rows[index], restartRequired };
  }

  // As the API answers: span and step per range, every series filled, and a memory reading.
  function metricsResponse(range: string) {
    const [span, step] = ({ live: [900, 60], "1h": [3600, 60], "24h": [86_400, 900], "7d": [604_800, 7200], "30d": [2_592_000, 21_600] } as Record<string, [number, number]>)[range];
    const count = span / step;
    const end = Date.parse("2026-11-15T10:00:00Z");
    const timestamps = Array.from({ length: count }, (_, i) => new Date(end - (count - 1 - i) * step * 1000).toISOString());
    const wave = (scale: number) => timestamps.map((_, i) => scale * (1 + (i % 5)));
    return {
      cores: 2,
      memoryGb: 4,
      timestamps,
      series: {
        cpu: wave(2), diskReadBytes: wave(1000), diskWriteBytes: wave(4000), diskReadIops: wave(0.5), diskWriteIops: wave(1),
        networkInBytes: wave(300), networkOutBytes: wave(900), networkInPackets: wave(2), networkOutPackets: wave(3),
      },
      memory: { totalBytes: 4 * 1024 ** 3, availableBytes: 3 * 1024 ** 3, reportedAt: "2026-11-15T09:59:50Z" },
    };
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
  return { connectors, connectorKeys, workspaceConnectors, drafts, launched, billing, mail, notifications, recipes, domains, firewalls, sessionFiles, instructions, tasks, variables, apiCalls, auth, restarted, sessionTtl, rows, secrets, sent, sessions, messages, forms, answers, prompts, aborted, mcp, mcpPosted, terminals, sessionAgents, agentOptions, catalogue, state, fetchFn };
}

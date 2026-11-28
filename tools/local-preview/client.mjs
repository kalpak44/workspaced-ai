/** Trusted, single-user local preview only. Never imported by the hosted app or VM harness. */
import { createHash, createPublicKey, randomBytes, randomUUID, timingSafeEqual, verify } from "node:crypto";
import { constants } from "node:fs";
import * as fs from "node:fs/promises";
import path from "node:path";

const ISSUER = "https://auth.openai.com";
const RESOURCE = "https://api.openai.com/v1";
const DIRECT = "chatgpt.tokens.use.direct";
const SCOPES = `openid profile email offline_access resource.invoke ${DIRECT}`;
const safeEqual = (a, b) => typeof a === "string" && typeof b === "string" &&
  a.length > 0 && Buffer.byteLength(a) === Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const random = () => randomBytes(32).toString("base64url");
const requireValue = (ok, message) => { if (!ok) throw new Error(message); };
const scopes = value => new Set(typeof value === "string" ? value.split(/\s+/) : []);
const endpoint = value => {
  const url = new URL(value);
  requireValue(url.origin === ISSUER && !url.username && !url.password && !url.hash, "Untrusted OpenAI endpoint");
  return url.href;
};

export class LocalPreview {
  constructor({ fetcher = fetch, now = () => Date.now() } = {}) {
    this.fetcher = fetcher;
    this.now = now;
    this.pending = new Map();
  }
  async request(url, options) {
    const response = await this.fetcher(url, { ...options, redirect: "error", signal: AbortSignal.timeout(30000) });
    // Provider error bodies may include sensitive values; never copy them to errors or logs.
    requireValue(response.ok, `OpenAI request failed (${response.status}); reconnect or check eligibility`);
    return response;
  }
  async discovery() {
    const d = await (await this.request(`${ISSUER}/.well-known/openid-configuration`)).json();
    requireValue(d.issuer === ISSUER, "Unexpected OpenAI issuer");
    return { issuer: d.issuer, authorization: endpoint(d.authorization_endpoint),
      token: endpoint(d.token_endpoint), jwks: endpoint(d.jwks_uri), revocation: endpoint(d.revocation_endpoint) };
  }
  async begin(host, redirect, account = null) {
    requireValue(/^urn:uuid:[0-9a-f-]{36}$/.test(host), "Invalid stable host ID");
    const callback = new URL(redirect);
    requireValue(callback.protocol === "http:" && callback.hostname === "127.0.0.1" &&
      callback.port && callback.pathname === "/auth/callback" && !callback.search && !callback.hash &&
      !callback.username && !callback.password, "A loopback callback is required");
    requireValue(!account || (account.clientId?.startsWith("oaiapp_") && account.subject), "Invalid saved registration");
    const discovery = await this.discovery();
    const attempt = { state: random(), nonce: random(), verifier: random(), host, redirect,
      discovery, account, deadline: this.now() + 300000 };
    this.pending.set(attempt.state, attempt);
    const url = new URL(discovery.authorization);
    url.search = new URLSearchParams({ client_id: account?.clientId ?? "dynamic_agent_client",
      ...(account ? {} : { agent_name_hint: "Workspaced Local Preview" }),
      ...(account?.idToken ? { id_token_hint: account.idToken } : {}),
      ext_agent_host_id: host, response_type: "code", redirect_uri: redirect, scope: SCOPES,
      resource: RESOURCE, state: attempt.state, nonce: attempt.nonce, code_challenge_method: "S256",
      code_challenge: createHash("sha256").update(attempt.verifier).digest("base64url") }).toString();
    return { url: url.href, state: attempt.state };
  }
  async identity(idToken, clientId, nonce, discovery, expectedSubject = null) {
    requireValue(typeof idToken === "string" && idToken.length < 65536, "Missing ID token");
    const parts = idToken.split(".");
    requireValue(parts.length === 3, "Invalid ID token");
    const header = JSON.parse(Buffer.from(parts[0], "base64url"));
    requireValue(header.alg === "RS256" && typeof header.kid === "string" && !header.crit, "Unsupported ID token signature");
    const keys = await (await this.request(discovery.jwks)).json();
    const candidates = keys.keys.filter(k => k.kid === header.kid && k.kty === "RSA" &&
      (!k.use || k.use === "sig") && (!k.alg || k.alg === "RS256"));
    requireValue(candidates.length === 1, "Unknown ID token signing key");
    requireValue(verify("RSA-SHA256", Buffer.from(`${parts[0]}.${parts[1]}`),
      createPublicKey({ key: candidates[0], format: "jwk" }), Buffer.from(parts[2], "base64url")), "Invalid ID token signature");
    const claims = JSON.parse(Buffer.from(parts[1], "base64url"));
    const audience = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
    requireValue(claims.iss === discovery.issuer && audience.includes(clientId) &&
      (audience.length === 1 || claims.azp === clientId) && (!claims.azp || claims.azp === clientId) &&
      Number.isFinite(claims.exp) && claims.exp * 1000 > this.now() &&
      (!claims.nbf || claims.nbf * 1000 <= this.now() + 30000) &&
      typeof claims.sub === "string" && claims.sub.length > 0, "Invalid ID token claims");
    if (nonce !== null) requireValue(safeEqual(claims.nonce, nonce), "Invalid ID token nonce");
    if (expectedSubject) requireValue(claims.sub === expectedSubject, "The selected account identity changed");
    return claims;
  }
  async finish(callbackUrl) {
    const callback = new URL(callbackUrl);
    const state = callback.searchParams.get("state");
    const attempt = this.pending.get(state);
    requireValue(attempt && safeEqual(state, attempt.state), "Invalid OAuth state");
    this.pending.delete(state); // One-shot even when denied, malformed, expired or exchange fails.
    requireValue(this.now() < attempt.deadline, "Sign-in expired");
    requireValue(callback.origin + callback.pathname === attempt.redirect, "Unexpected OAuth callback");
    for (const key of ["state", "code", "client_id", "error"]) requireValue(callback.searchParams.getAll(key).length <= 1, "Ambiguous OAuth callback");
    requireValue(!callback.searchParams.has("error"), "ChatGPT sign-in was declined");
    const clientId = callback.searchParams.get("client_id") ?? attempt.account?.clientId;
    requireValue(clientId?.startsWith("oaiapp_") && (!attempt.account || clientId === attempt.account.clientId), "Invalid issued client ID");
    const code = callback.searchParams.get("code");
    requireValue(code, "Missing authorization code");
    const tokens = await (await this.request(attempt.discovery.token, { method: "POST",
      body: new URLSearchParams({ grant_type: "authorization_code", client_id: clientId, code,
        code_verifier: attempt.verifier, redirect_uri: attempt.redirect, resource: RESOURCE }) })).json();
    return this.account(tokens, clientId, attempt.discovery, attempt.nonce, attempt.account);
  }
  async account(tokens, clientId, discovery, nonce, previous) {
    requireValue(scopes(tokens.scope).has(DIRECT) && scopes(tokens.scope).has("resource.invoke"), "ChatGPT plan usage was not granted");
    requireValue(tokens.token_type?.toLowerCase() === "bearer" && typeof tokens.access_token === "string" &&
      tokens.access_token && Number.isFinite(tokens.expires_in) && tokens.expires_in > 0, "Incomplete OAuth token response");
    const idToken = tokens.id_token ?? (nonce === null ? previous?.idToken : null);
    let identity;
    if (tokens.id_token) identity = await this.identity(idToken, clientId, nonce, discovery, previous?.subject);
    else requireValue(nonce === null && previous?.subject, "Missing ID token");
    return { id: previous?.id ?? randomUUID(), clientId, subject: identity?.sub ?? previous.subject,
      accessToken: tokens.access_token, refreshToken: tokens.refresh_token ?? previous?.refreshToken,
      idToken, scope: tokens.scope, expiresAt: this.now() + tokens.expires_in * 1000 };
  }
  async refresh(account) {
    requireValue(account.refreshToken && account.clientId.startsWith("oaiapp_"), "Reconnect this account");
    const d = await this.discovery();
    const tokens = await (await this.request(d.token, { method: "POST", body: new URLSearchParams({
      grant_type: "refresh_token", client_id: account.clientId, refresh_token: account.refreshToken, resource: RESOURCE }) })).json();
    // OAuth may omit unchanged granted scope on refresh.
    tokens.scope ??= account.scope;
    return this.account(tokens, account.clientId, d, null, account);
  }
  async models(account) {
    const response = await (await this.request(`${RESOURCE}/models`, { headers: { Authorization: `Bearer ${account.accessToken}` } })).json();
    requireValue(Array.isArray(response.models), "Unexpected ChatGPT model catalog");
    return response.models.filter(m => m.visibility === "list" && typeof m.slug === "string");
  }
  async infer(account, model) {
    requireValue((await this.models(account)).some(m => m.slug === model), "Model is unavailable to this account");
    const response = await this.request(`${RESOURCE}/responses`, { method: "POST",
      headers: { Authorization: `Bearer ${account.accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model, input: [{ role: "user", content: "Reply with exactly WORKSPACED_OK." }], store: false, stream: true }) });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "", completed = null;
    const consume = line => {
      if (!line.startsWith("data:")) return;
      const data = line.slice(5).trim(); if (!data || data === "[DONE]") return;
      const event = JSON.parse(data);
      requireValue(!["response.failed", "response.incomplete", "error"].includes(event.type), "Inference did not complete successfully");
      if (event.type === "response.completed") completed = event.response;
    };
    try {
      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        requireValue(buffer.length < 1048576, "Oversized response stream event");
        let newline;
        while ((newline = buffer.indexOf("\n")) >= 0) { consume(buffer.slice(0, newline).trimEnd()); buffer = buffer.slice(newline + 1); }
        if (done) break;
      }
      if (buffer) consume(buffer);
      requireValue(completed?.status === "completed" && completed.model && completed.usage &&
        Number.isSafeInteger(completed.usage.input_tokens) && completed.usage.input_tokens > 0 &&
        Number.isSafeInteger(completed.usage.output_tokens) && completed.usage.output_tokens > 0, "Missing completed token receipt");
      return { model: completed.model, input: completed.usage.input_tokens, output: completed.usage.output_tokens };
    } finally { await reader.cancel(); }
  }
  async revoke(account) {
    if (!account.refreshToken) return false;
    const d = await this.discovery();
    await this.request(d.revocation, { method: "POST", body: new URLSearchParams({
      token: account.refreshToken, token_type_hint: "refresh_token", client_id: account.clientId }) });
    return true;
  }
}

/** Lock the whole local operation, including rotating refresh and atomic credential replacement. */
export async function withState(directory, action) {
  await fs.mkdir(directory, { recursive: true, mode: 0o700 });
  const stat = await fs.lstat(directory);
  requireValue(stat.isDirectory() && !stat.isSymbolicLink() && stat.uid === process.getuid() && (stat.mode & 0o077) === 0, "Local state directory must be private (0700)");
  const flags = constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW;
  let lock;
  try { lock = await fs.open(path.join(directory, "operation.lock"), flags, 0o600); }
  catch { throw new Error("Another local preview operation is running (or a crashed operation left its lock)"); }
  const store = {
    async read(name) {
      requireValue(/^(host|[0-9a-f-]{36})\.json$/.test(name), "Invalid local record name");
      try {
        const handle = await fs.open(path.join(directory, name), constants.O_RDONLY | constants.O_NOFOLLOW);
        try { const st = await handle.stat(); requireValue(st.isFile() && st.uid === process.getuid() && !(st.mode & 0o077), "Unsafe credential file permissions"); return JSON.parse(await handle.readFile("utf8")); }
        finally { await handle.close(); }
      } catch (e) { if (e.code === "ENOENT") return null; throw e; }
    },
    async write(name, value) {
      requireValue(/^(host|[0-9a-f-]{36})\.json$/.test(name), "Invalid local record name");
      const temporary = path.join(directory, `.${randomUUID()}.tmp`);
      const handle = await fs.open(temporary, flags, 0o600);
      try { await handle.writeFile(JSON.stringify(value)); await handle.sync(); }
      finally { await handle.close(); }
      await fs.rename(temporary, path.join(directory, name));
    },
  };
  try {
    let host = await store.read("host.json");
    if (!host) { host = { host: `urn:uuid:${randomUUID()}` }; await store.write("host.json", host); }
    return await action(store, host.host);
  } finally { await lock.close(); await fs.unlink(path.join(directory, "operation.lock")); }
}

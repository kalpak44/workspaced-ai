import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, randomUUID } from "node:crypto";
import * as fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { LocalPreview, withState } from "./client.mjs";
import { signIn } from "./pilot.mjs";
const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "test-key", alg: "RS256", use: "sig" };
const ISSUER = "https://auth.openai.com";
const DIRECT = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
const discovery = { issuer: ISSUER, authorization_endpoint: `${ISSUER}/api/accounts/authorize`, token_endpoint: `${ISSUER}/api/accounts/oauth/token`, jwks_uri: `${ISSUER}/.well-known/jwks.json`, revocation_endpoint: `${ISSUER}/oauth/revoke` };
const host = `urn:uuid:${randomUUID()}`;
function jwt(claims, header = {}) {
  const parts = [Buffer.from(JSON.stringify({ alg: "RS256", kid: "test-key", ...header })).toString("base64url"),
    Buffer.from(JSON.stringify({ iss: ISSUER, aud: "oaiapp_fixture", sub: "fixture-subject", exp: 9999999999, ...claims })).toString("base64url")];
  return `${parts.join(".")}.${sign("RSA-SHA256", Buffer.from(parts.join(".")), privateKey).toString("base64url")}`;
}
function fixture(overrides = {}) {
  const calls = []; let nonce;
  const client = new LocalPreview({ fetcher: async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("openid-configuration")) return Response.json({ ...discovery, ...overrides.discovery });
    if (url.endsWith("jwks.json")) return Response.json({ keys: [jwk] });
    if (url.endsWith("/oauth/token")) return Response.json({ access_token: "SYNTHETIC_ACCESS", refresh_token: "SYNTHETIC_REFRESH", token_type: "Bearer", expires_in: 3600,
      scope: DIRECT, id_token: jwt({ nonce, ...overrides.claims }, overrides.header), ...overrides.tokens });
    if (url.endsWith("/oauth/revoke")) return new Response(null, { status: overrides.revokeStatus ?? 200 });
    if (url.endsWith("/models")) return Response.json({ models: [{ slug: "fixture-model", visibility: "list" }, { slug: "hidden-model", visibility: "hidden" }] });
    if (url.endsWith("/responses")) return new Response(overrides.stream ?? `data: ${JSON.stringify({ type: "response.completed", response: { status: "completed", model: "fixture-model", usage: { input_tokens: 12, output_tokens: 7 } } })}\n\n`);
    throw new Error("Unexpected fixture URL; no request may leave this mock");
  }});
  const begin = async account => {
    const attempt = await client.begin(host, "http://127.0.0.1:12345/auth/callback", account);
    nonce = new URL(attempt.url).searchParams.get("nonce"); return attempt;
  };
  const finish = async (attempt, extra = "") => client.finish(`http://127.0.0.1:12345/auth/callback?code=fixture-code&state=${attempt.state}&client_id=oaiapp_fixture${extra}`);
  return { client, calls, begin, finish, setNonce: value => { nonce = value; } };
}

test("new dynamic registration validates signed identity and sends exact PKCE/callback/resource; replay is refused", async () => {
  const f = fixture(); const attempt = await f.begin(); const url = new URL(attempt.url);
  assert.equal(url.searchParams.get("client_id"), "dynamic_agent_client");
  assert.equal(url.searchParams.get("agent_name_hint"), "Workspaced Local Preview");
  assert.equal(url.searchParams.get("ext_agent_host_id"), host);
  assert.equal(url.searchParams.get("code_challenge_method"), "S256");
  const account = await f.finish(attempt);
  assert.equal(account.clientId, "oaiapp_fixture"); assert.equal(account.subject, "fixture-subject");
  const body = f.calls.find(c => c.url.endsWith("/oauth/token")).options.body;
  assert.equal(body.get("client_id"), "oaiapp_fixture"); assert.equal(body.get("redirect_uri"), "http://127.0.0.1:12345/auth/callback");
  assert.equal(body.get("resource"), "https://api.openai.com/v1"); assert.equal(body.has("client_secret"), false);
  await assert.rejects(f.finish(attempt), /state/);
});

test("returning registration reuses issued client and host; a different account/client is refused", async () => {
  const f = fixture(); const first = await f.finish(await f.begin()); const attempt = await f.begin(first);
  const url = new URL(attempt.url);
  assert.equal(url.searchParams.get("client_id"), first.clientId); assert.equal(url.searchParams.has("agent_name_hint"), false);
  assert.equal(url.searchParams.get("id_token_hint"), first.idToken);
  assert.equal((await f.finish(attempt)).id, first.id);
  const bad = await f.begin(first);
  await assert.rejects(f.client.finish(`http://127.0.0.1:12345/auth/callback?code=x&state=${bad.state}&client_id=oaiapp_other`), /client/);
  const other = fixture({ claims: { sub: "other-person" } });
  await assert.rejects(other.finish(await other.begin(first)), /identity/);
});

for (const [label, override] of [
  ["issuer", { claims: { iss: "https://evil.invalid" } }], ["audience", { claims: { aud: "oaiapp_other" } }],
  ["authorized party", { claims: { azp: "oaiapp_other" } }], ["expiry", { claims: { exp: 1 } }], ["nonce", { claims: { nonce: "wrong" } }],
  ["algorithm", { header: { alg: "none" } }], ["unknown key", { header: { kid: "unknown" } }],
  ["missing plan grant", { tokens: { scope: "openid profile" } }], ["missing identity", { tokens: { id_token: undefined } }],
]) test(`rejects ${label} without accepting an account`, async () => {
  const f = fixture(override); await assert.rejects(f.finish(await f.begin()));
});

test("state, denial, duplicate parameters and timeout are rejected before token exchange", async () => {
  const f = fixture(); const attempt = await f.begin();
  await assert.rejects(f.client.finish("http://127.0.0.1:12345/auth/callback?state=bad&code=x"));
  await assert.rejects(f.finish(attempt, "&error=access_denied"));
  assert.equal(f.calls.some(c => c.url.endsWith("/oauth/token")), false);
  await assert.rejects(f.finish(await f.begin(), "&code=duplicate"));
  const expired = await f.begin(); f.client.now = () => Date.now() + 400000;
  await assert.rejects(f.finish(expired), /expired/);
});

test("discovery cannot redirect token/JWKS/revocation traffic to an unrelated origin", async () => {
  const f = fixture({ discovery: { jwks_uri: "https://evil.invalid/keys" } });
  await assert.rejects(f.begin(), /endpoint/);
  await assert.rejects(f.client.begin(host, "http://localhost:12345/auth/callback"), /loopback/);
});

test("refresh uses saved issued registration, rotates tokens, and never re-registers", async () => {
  const f = fixture(); const account = await f.finish(await f.begin());
  const refreshed = await f.client.refresh(account);
  assert.equal(refreshed.id, account.id);
  const body = f.calls.filter(c => c.url.endsWith("/oauth/token")).at(-1).options.body;
  assert.equal(body.get("grant_type"), "refresh_token"); assert.equal(body.get("client_id"), account.clientId);
  assert.equal(body.has("scope"), false);
  assert.equal(await f.client.revoke(refreshed), true);
});

test("account-specific catalog and public Responses request return only completed measured tokens", async () => {
  const f = fixture(); const account = await f.finish(await f.begin());
  assert.deepEqual(await f.client.infer(account, "fixture-model"), { model: "fixture-model", input: 12, output: 7 });
  const call = f.calls.find(c => c.url.endsWith("/responses"));
  assert.equal(call.url, "https://api.openai.com/v1/responses");
  assert.deepEqual(JSON.parse(call.options.body), { model: "fixture-model", input: [{ role: "user", content: "Reply with exactly WORKSPACED_OK." }], store: false, stream: true });
  await assert.rejects(f.client.infer(account, "hidden-model"), /unavailable/);
});
for (const stream of ["data: [DONE]\n\n", 'data: {"type":"response.failed"}\n\n', 'data: {"type":"response.completed","response":{"status":"completed"}}\n\n']) {
  test("missing/failed/truncated completion never produces usage or success", async () => {
    const f = fixture({ stream }); const account = await f.finish(await f.begin());
    await assert.rejects(f.client.infer(account, "fixture-model"));
  });
}

test("private state persists host across restarts, locks concurrent refresh, and rejects unsafe files", async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), "workspaced-preview-test-")); const dir = path.join(temp, "state");
  try {
    let first;
    await withState(dir, async (store, hostId) => {
      first = hostId; await store.write(`${randomUUID()}.json`, { accessToken: "SYNTHETIC" });
      await assert.rejects(withState(dir, () => {}), /running/);
    });
    await withState(dir, async (_, hostId) => assert.equal(hostId, first));
    assert.equal((await fs.stat(path.join(dir, "host.json"))).mode & 0o777, 0o600);
    await fs.chmod(path.join(dir, "host.json"), 0o644);
    await assert.rejects(withState(dir, () => {}), /permissions/);
    await fs.unlink(path.join(dir, "host.json")); await fs.symlink(path.join(temp, "outside"), path.join(dir, "host.json"));
    await assert.rejects(withState(dir, () => {}));
  } finally { await fs.rm(temp, { recursive: true, force: true }); }
});

test("real loopback entry point validates the callback and closes its listener", async () => {
  const f = fixture(); let redirect;
  const account = await signIn(f.client, host, null, async url => {
    const auth = new URL(url); redirect = auth.searchParams.get("redirect_uri");
    f.setNonce(auth.searchParams.get("nonce"));
    const response = await fetch(`${redirect}?state=${auth.searchParams.get("state")}&code=fixture&client_id=oaiapp_fixture`);
    assert.equal(response.status, 200); assert.equal(response.headers.get("cache-control"), "no-store");
  });
  assert.equal(account.clientId, "oaiapp_fixture");
  await assert.rejects(fetch(redirect));
});

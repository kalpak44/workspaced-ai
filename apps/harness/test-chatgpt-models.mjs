import { test } from 'node:test';
import assert from 'node:assert/strict';
import plugin from './opencode/chatgpt-models/index.mjs';

test('ChatGPT catalog uses visible account entitlements, default wire alias, and fails closed', async () => {
  const originalFetch = globalThis.fetch;
  let fail = false;
  let transform;
  let listener;
  let wake;
  let active = false;
  let token = 'test-token-a';
  let models = [{ slug: 'gpt-6-luna', display_name: 'GPT-6-Luna', visibility: 'list', supported_in_api: true, context_window: 200000 },
    { slug: 'codex-auto-review', visibility: 'hide', supported_in_api: true },
    { slug: 'gpt-5.6-sol', visibility: 'list', supported_in_api: false }];
  globalThis.fetch = async (url, init) => {
    assert.equal(new URL(url).origin, 'https://chatgpt.com');
    assert.equal(init.headers.Authorization, `Bearer ${token}`);
    return { ok: !fail, json: async () => ({ models }) };
  };
  let entries;
  function rebuild() {
    entries = new Map(['gpt-5.6-sol', 'gpt-6.1-sol', 'gpt-6-luna-pro'].map(id => [id, { id, modelID: id, enabled: true, capabilities: { tools: true }, limit: { context: 100000 } }]));
    transform({ list: () => [...entries.values()], update: (_, id, fn) => {
      if (!entries.has(id)) entries.set(id, { id, modelID: id, limit: {} });
      fn(entries.get(id));
    } });
  }
  const cleanup = await plugin.setup({
    integration: { connection: { active: async () => active ? { id: 'account' } : undefined,
      resolve: async () => ({ type: 'oauth', methodID: 'chatgpt-headless', access: token, metadata: { accountID: 'test-account' } }) } },
    model: { transform: async fn => { transform = fn; rebuild(); }, reload: async () => { rebuild(); wake?.(); } },
    event: { subscribe: ({ signal }) => ({ [Symbol.asyncIterator]() { return { next: () => new Promise(resolve => {
      listener = resolve; signal.addEventListener('abort', () => resolve({ done: true }), { once: true });
    }) }; } }) },
  });
  try {
    assert.equal(entries.get('workspaced-chatgpt').enabled, true); // readiness before sign-in
    async function switched() {
      const updated = new Promise(resolve => { wake = resolve; });
      listener({ value: { data: { integrationID: 'openai' } }, done: false });
      await updated;
      await new Promise(resolve => setImmediate(resolve));
    }
    active = true;
    await switched();
    assert.deepEqual([...entries.values()].filter(m => m.enabled).map(m => m.id), ['gpt-6-luna', 'workspaced-chatgpt']);
    assert.equal(entries.get('workspaced-chatgpt').modelID, 'gpt-6-luna');
    token = 'rotated-token-b';
    models = [{ slug: 'gpt-5.6-terra', display_name: 'GPT-5.6-Terra', visibility: 'list', supported_in_api: true }];
    await switched();
    assert.equal(entries.get('workspaced-chatgpt').modelID, 'gpt-5.6-terra');
    assert.equal(entries.get('gpt-6-luna')?.enabled ?? false, false);
    fail = true;
    await switched();
    assert.deepEqual([...entries.values()].filter(m => m.enabled), []);
  } finally { cleanup(); globalThis.fetch = originalFetch; }
});

// Account discovery stays beside OpenCode's refreshed OAuth credential. Never persist tokens,
// log provider responses, or fall back to an API-key catalog on discovery failure.
const DEFAULT = "workspaced-chatgpt";
export default {
  id: "workspaced.chatgpt-models",
  async setup(ctx) {
    let accountModels = [];
    let connected = false;
    let running;
    const refresh = () => running ??= (async () => {
      try {
        const connection = await ctx.integration.connection.active("openai");
        const credential = connection && await ctx.integration.connection.resolve(connection);
        connected = credential?.type === "oauth" && credential.methodID === "chatgpt-headless";
        accountModels = [];
        if (!connected) return;
        const response = await fetch("https://chatgpt.com/backend-api/codex/models?client_version=0.172.0", {
          headers: {
            Authorization: `Bearer ${credential.access}`,
            originator: "opencode",
            ...(credential.metadata?.accountID ? { "chatgpt-account-id": credential.metadata.accountID } : {}),
          },
          signal: AbortSignal.timeout(15000),
        });
        if (!response.ok) return;
        const data = await response.json();
        const current = await ctx.integration.connection.active("openai");
        if (JSON.stringify(current) !== JSON.stringify(connection)) return;
        accountModels = (data.models ?? []).filter(m => m.visibility === "list" && m.supported_in_api === true && typeof m.slug === "string" && /^gpt-[a-zA-Z0-9.-]+$/.test(m.slug));
      } catch { accountModels = []; }
    })().finally(() => { running = undefined; });
    await refresh();
    await ctx.model.transform(editor => {
      const known = editor.list("openai");
      const template = known.find(m => m.id === "gpt-5.6-sol");
      for (const model of known) editor.update("openai", model.id, draft => { draft.enabled = false; });
      for (const remote of accountModels) editor.update("openai", remote.slug, draft => {
        if (template) Object.assign(draft, structuredClone(template));
        draft.id = remote.slug;
        draft.modelID = remote.slug;
        draft.name = remote.display_name || remote.slug;
        draft.enabled = true;
        draft.cost = [];
        if (Number.isInteger(remote.context_window)) draft.limit.context = remote.context_window;
      });
      // The managed main/title config names this alias; its wire model always comes from
      // discovery. Before sign-in it exists for readiness only; the API blocks all prompts.
      editor.update("openai", DEFAULT, draft => {
        if (template) Object.assign(draft, structuredClone(template));
        draft.id = DEFAULT;
        draft.modelID = accountModels[0]?.slug ?? DEFAULT;
        draft.name = accountModels[0]?.display_name ? `${accountModels[0].display_name} (ChatGPT default)` : "ChatGPT default";
        draft.enabled = !connected || accountModels.length > 0;
        draft.cost = [];
      });
    });
    const update = async () => { await refresh(); await ctx.model.reload(); };
    const abort = new AbortController();
    const events = (async () => {
      for await (const event of ctx.event.subscribe({ types: ["credential.switched"], signal: abort.signal })) {
        if (event.data.integrationID === "openai") await update();
      }
    })().catch(() => undefined);
    const timer = setInterval(() => { void update().catch(() => undefined); }, 60000);
    return () => { clearInterval(timer); abort.abort(); void events; };
  },
};

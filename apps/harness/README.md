# Harness provider profiles

The single harness image is pinned to OpenCode **2.2.5**. Start it with the trusted
runtime environment variable `WORKSPACED_AI_PROVIDER`:

| Value | Allowed provider | Default model | Title model |
| --- | --- | --- | --- |
| Unset or `openrouter` | `openrouter` | `openrouter/deepseek/deepseek-v4.1-flash` | `openrouter/anthropic/claude-haiku-5.5` |
| `openai` | `openai` | `openai/gpt-6.1-sol` | `openai/gpt-6.1-sol` |
| `openai-chatgpt` | `openai` | Account-discovered ChatGPT default | Same account-discovered default |

An empty or unknown value exits with status 2 before the server or user services
start. There is no provider fallback. The OpenRouter profile retains
`OPENROUTER_BASE_URL` and its `HTTP-Referer` / `X-Title` headers. The OpenAI profile
adds no OpenRouter settings or routing. Both retain the harness's shared tool
permissions, instructions, sharing and update settings.

Change the runtime selector and recreate the container to switch profiles; it is
not a hot switch or a setting read from a project. No API/UI selection or VM-manager
persistence is added by this slice. The workspace `.env` cannot set the selector,
and its existing `OPENCODE_*` filter prevents config-file/directory/content overrides.

## Configuration and restart

`opencode/common.json` and `opencode/profiles/*.json` are baked, non-secret templates.
Every `start-harness` reconstructs the selected config in
`/tmp/workspaced-opencode/opencode.json`. The image's `/etc/opencode` symlink points
there, so OpenCode discovers it as **system-managed** configuration while the root
filesystem stays read-only. Keep the existing writable, executable `/tmp` mount.
The config directory is writable because OpenCode creates config metadata there.
It is not copied into the global directory as well.

Pinned 2.2.5 loads system settings above global/project values and system policies
above their policies. An explicit deny-all-provider statement followed by the one
allowed provider prevents project/user configuration from enabling another provider.
Both the main and title model are managed; lower-priority config cannot change them.
These semantics are checked against the actual binary, not just the current
[configuration](https://opencode.ai/docs/config/) and
[policy](https://opencode.ai/docs/policies) documentation.

Startup removes the legacy harness-owned `~/.config/opencode/opencode.json` slot,
which older images replaced every start. This prevents the old defaults from loading
again or carrying a previous profile. Put user settings and MCP servers in
`~/.config/opencode/opencode.jsonc`, as before. That file, user instruction files,
plugins, sessions and project data remain intact. The pre-existing V1 plugin migration
is unchanged. Edits to the generated `/tmp` config do not survive the next start.

A profile is configuration, **not OAuth or a security sandbox**. ChatGPT authentication is
managed by the workspace-bound connection flow in the API and OpenCode. The ChatGPT profile
keeps its Codex-compatible default independent of the API-key profile; the profile alone
does not authorize model calls. A newly created ChatGPT workspace starts without either
provider API key and its API rejects chat until sign-in has been installed.
There are no credentials in these templates. A user with shell access can run their
own processes or alter writable runtime files; provider policies constrain the
configured OpenCode server, not the container's general network or shell access.

## Offline checks

```sh
docker build --platform linux/amd64 -t workspaced-harness:provider-profiles apps/harness
python3 apps/harness/test-profiles.py workspaced-harness:provider-profiles
```

The test runs the image's real `start-harness` entry point with a read-only root,
no capabilities, writable home and executable `/tmp`, and **no external network**.
It uses inert placeholder keys for catalog discovery, never model requests or sign-in.
It checks the exact pin, default legacy behavior, both explicit profiles, custom
OpenRouter URL/headers, effective provider catalog/default/title models, invalid/empty
selectors, one defaults source, conflicting project/global config, `.env` filtering,
and successive starts/restarts sharing an edited persisted home. Disposable containers
and the test volume are removed afterward. The Docker build also parses both profiles
with pinned OpenCode. Existing CI's default-provider API/PTY smoke check remains valid;
the workflow is generated elsewhere, so this change does not edit it.

These tests establish configuration and startup behavior. They do not establish
provider authentication, successful inference/title generation, subscription eligibility,
or production deployment behavior.

`python3 apps/harness/test-completed-usage.py` additionally exercises one model
request against a synthetic loopback Responses server in a `--network none`
container. It proves explicit dispatch IDs and completed model/token receipts
against 2.2.5 with an inert key. It is not provider authentication or a live
subscription test. See [Workspaced usage](../../docs/ai-usage.md) and the isolated
[local-preview pilot](../../tools/local-preview/README.md).

## ChatGPT account models

The managed `workspaced-chatgpt` model is a runtime alias for the first visible, API-supported
model returned by the connected account's Codex model catalog. The same alias drives titles.
The bundled dependency-free plugin discovers models using OpenCode's current, refreshed OAuth
credential inside the workspace. It refreshes on credential changes and every minute, hides
all other static OpenAI models and hidden account models, and never persists or logs tokens.
After sign-in, failed or empty discovery disables all ChatGPT models rather than using an
API-key catalog. Existing unsupported workspace selections resolve to this account default.

`node --test test-chatgpt-models.mjs` covers account filtering, token rotation, default wire
mapping, and failed discovery. `test-profiles.py` also runs this regression test in CI.

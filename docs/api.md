# API

The whole app is served under the `/api/v1` context path, so the host root is free for the frontend: Swagger UI is at
`/api/v1/swagger-ui`, the OpenAPI 3.0 document at `/api/v1/v3/api-docs`, health at `/api/v1/actuator/health`.

## Authentication and tenancy

Auth0 signs users in; the API keeps no passwords. A browser goes to `GET /oauth2/authorization/auth0` (add `?signup` to
open Auth0's sign-up form, `?mfa=enroll` to set up two-factor authentication), signs in on Auth0's page, and comes back to
`/login/oauth2/code/auth0`. There the API finds the account by its Auth0 subject or creates one, takes the role Auth0
has for the user now, and redirects to `/` (`/?welcome` for a new account, which also gets a welcome notification and
mail). The user is an admin when Auth0 assigns them its `admin` role, which arrives in the ID token's
`https://workspaced.online/roles` claim. The request in flight between the two legs is kept in an encrypted cookie rather
than a server session, so the callback may reach any instance; any failure there goes to `/login?error=failed`.

An email Auth0 has not verified is refused: the API ends the Auth0 session, so the next sign-in asks again, and sends the
browser to the app's `/verify-email`, setting a 30-minute `__Host-workspaced_verify` cookie that names the sign-up.
`POST /auth/verification-email` then has Auth0 send the verification email again: `204`, `429` within a minute of the
last one, or `401` once the cookie has expired.

Every other route needs a signed-in user, except `POST /auth/logout`, `GET /auth/end-session`, `GET /auth/csrf`, health
and the API docs. After Auth0's sign-in the API issues its own HS256 JWT (Spring Security's OAuth2 resource server
validates it): its subject is the user's id, and it carries the profile Auth0 vouched for, so no page asks Auth0 for it.
It is set as the `__Host-workspaced_token` cookie — HttpOnly, Secure, SameSite=Strict, `Path=/` — which is how the browser
authenticates, EventSource and the terminal WebSocket included, since neither can set a header. Other clients send
`Authorization: Bearer <token>`. Users run what they like on VMs named under the same site, so every cookie is `__Host-`,
which a sibling origin can neither set nor shadow, and a change (any method but GET) sent by a browser must come from this
origin, by its `Sec-Fetch-Site` or `Origin`, else `403`. It must also carry the CSRF token in the `X-XSRF-TOKEN` header —
never a form field — else `403`: `GET /auth/csrf` answers it and sets the HttpOnly `__Host-XSRF-TOKEN` cookie it is
checked against. Bearer requests need neither. `POST /auth/logout` clears the cookie (`204`) and revokes every session
of its account, on every device: a token issued before then answers `401`. The browser then opens `GET
/auth/end-session`, which clears the cookie too and redirects to Auth0's logout, so the next sign-in asks for a password
again, and from there to the public site. A missing, invalid, expired or revoked token answers `401` as a problem
detail.

The database keeps no personal data: an account is its Auth0 subject and its role. `GET /account` returns the signed-in
user (`id`, `email`, `firstName`, `lastName`, `avatar`, `role`) from the session token and the role from the database;
`avatar` is Auth0's picture URL, the Gravatar for the email or a picture of the initials. `PUT /account/profile` saves the
first and last name (at most 100 characters each, trimmed) to the Auth0 profile and answers a new session cookie carrying
them. Recipe authors' names are read from Auth0 as recipes are shown, and an author without one shows as `Unknown`.
`POST /account/password-reset` has Auth0 mail the user a link to choose a new password: `204`, or `502` when Auth0 cannot
be reached. Accounts are managed in Auth0; the API has no user administration.

The app is multi-tenant by workspace. Each workspace has an owner and may have collaborators (see
[Workspace collaborators](#workspace-collaborators)); `GET /workspaces` and the `/workspaces/events` stream show only
those the caller is on, and every route under `/workspaces/{id}` — sessions, secrets, settings, terminals — is checked
against them before it runs. Anyone else's workspace answers `404`, exactly as one that does not exist.

| Variable                | Default | Meaning                                                                       |
|-------------------------|---------|-------------------------------------------------------------------------------|
| `WORKSPACED_JWT_SECRET` | none    | Signs the session tokens and download links, and keys the sign-in cookie; at least 32 bytes, the same on every instance. Required: the API does not start without it. |
| `WORKSPACED_TOKEN_TTL`  | `12h`   | How long a sign-in lasts.                                                     |
| `WORKSPACED_AUTH_CLIENT_SECRET` | none | The Auth0 application's secret. Required: the API does not start without it. |
| `WORKSPACED_AUTH_CLIENT_ID` | none | The Auth0 application the API signs users in as. Required. |
| `WORKSPACED_AUTH_ISSUER` | none | The Auth0 tenant, ending in a slash, e.g. `https://auth.workspaced.online/`. Required. |
| `WORKSPACED_AUTH_MANAGEMENT_API` | none | Auth0's Management API on the tenant's own domain, e.g. `https://workspaced.eu.auth0.com/api/v2/`. Required. |
| `WORKSPACED_APP_URL` | `https://ai.workspaced.online` | The web app: mail links and the page an unverified sign-up lands on. |
| `WORKSPACED_SITE_URL` | `https://workspaced.online` | The public site, where signing out ends. |

## OpenCode API reference

The workspace harness uses the [OpenCode V2 HTTP API documentation](https://dev.opencode.ai/v2/docs/api).
Its OpenAPI 3.1 specification is available as a [local JSON snapshot](opencode-openapi.json)
and as the [upstream OpenAPI JSON](https://dev.opencode.ai/v2/openapi.json).
The local snapshot was retrieved on 2026-11-04; it describes OpenCode's experimental API, whose schema
version is `0.0.1`, rather than the Workspaced API documented below. Check the deployed harness version
before relying on newer upstream operations.

## Model providers

`GET /api/v1/model-providers` lists the providers a workspace can be created with: `id`, `name`, `baseUrl`, and
`apiKeyVariable`, the secret the workspace receives the key in. The list is hardcoded — OpenRouter only for now —
because each provider needs its own key check.

Compute providers are the same kind of list for where a workspace's VM runs, but backend-only: no route lists them,
and nothing the web app reads names one. See [vm-manager.md](vm-manager.md#compute-providers).

## Workspaces

A workspace has an `id` (UUID, assigned by the server), a `name` (1–100 characters, surrounding whitespace trimmed),
the `modelProvider` it was created with, the `serverType` of its VM, and a `status`: `INITIALIZING` while its VM is
created, boots or reboots, `READY` once the VM has reported ready, `FAILED` if it has no VM. Workspaces are listed in
the order they were created.

| Method   | Path                      | Body               | Answers                                         |
|----------|---------------------------|--------------------|-------------------------------------------------|
| `GET`    | `/api/v1/vm-sizes`        |                    | `200` and the VM sizes, cheapest first, each with its monthly price and whether it is available now; `503` without the VM manager |
| `GET`    | `/api/v1/workspaces`      |                    | `200` and every workspace                       |
| `GET`    | `/api/v1/workspaces/events` |                  | `200` and a server-sent event stream            |
| `POST`   | `/api/v1/workspaces`      | `{"name": "Docs", "modelProvider": "openrouter", "serverType": "cx23"}` | `201`, the workspace, and its URL in `Location` |
| `PUT`    | `/api/v1/workspaces/{id}` | `{"name": "Docs"}` | `200` and the renamed workspace, or `404`       |
| `DELETE` | `/api/v1/workspaces/{id}` |                    | `204` once its VM is deleted, or `404`          |
| `POST`   | `/api/v1/workspaces/{id}/restart` |            | `202` while its harness restarts, `404`, or `409` with no VM or while `PAUSED` |

`/vm-sizes` lists the plans in `workspaced.plans.offered`: Starter (cx23), Builder (cx33) and Pro (cx43), each a fixed
daily price before VAT. An admin sees what the plan's VM costs, server and IPv4 address; everyone else sees the plan's
price. A plan marked `fallback` (Starter Flex, cpx22) is listed, as the default and with `recommended` false, only
while every other plan is sold out. A plan is listed as unavailable while its server is sold out, and also while its price, less Stripe's fee, no
longer keeps `min-margin-percent` over the server's cost with VAT times `hourly-billing-factor`; the API logs an error
then, so a Hetzner price rise withholds the plan instead of selling it at a loss. The site's `pricing.html` states the
same prices, so a change to one is a change to both.

A user who is not an admin pays for workspaces from their balance. Creating one needs a balance that covers
`WORKSPACED_BILLING_REQUIRE_COVERED` (7 days by default, returned as `requiredHours`) of every workspace they have plus
the new one at their price, or answers `402` saying how much, and nothing is created. From then on each workspace is
charged by the hour, its monthly price over 730 hours, for every hour as it starts; the price is fixed when the
workspace is created. When the balance cannot pay a workspace's next hour, the workspace is deleted. An admin's
workspaces are never charged.

A blank or overlong name, an unknown provider, a `serverType` `/vm-sizes` does not list, or an `id` that is not a UUID,
answers `400`; a `serverType` Hetzner has sold out answers `409`, and nothing is created. Every error body is an RFC 9457
problem detail (`application/problem+json`).

`apiKey` is required. Creating a workspace first calls the provider with it (OpenRouter's `GET /key`): a key the
provider refuses answers `422` and creates nothing, and a provider that cannot be reached answers `502`. The key goes
to the VM manager, which keeps it in Vault as the workspace's provider secret; the API never stores it.

Without `apiKey` the workspace is a **draft** (`status: DRAFT`): it gets an id and passes the same size and balance
checks, but has no VM, is not listed or charged, and announces nothing. `POST /workspaces/$ID/launch` with
`{"apiKey":"…"}` checks the key and creates the VM as a create with a key would have; a rejected key (`422`) or a failed
VM leaves it a draft to launch again, and launching one that is not a draft answers `409`. The web app drafts first so
the key OpenRouter issues can be labelled with the workspace id. Deleting a draft only drops its row, and `DraftCleanup`
deletes drafts older than `workspaced.workspace.draft-max-age` (an hour) that nobody launched or deleted.

```sh
curl -X POST localhost:8080/api/v1/workspaces -H 'Content-Type: application/json' \
  -d '{"name":"Docs","modelProvider":"openrouter","apiKey":"sk-or-v1-…","serverType":"cx23"}'
curl localhost:8080/api/v1/workspaces
curl -X PUT localhost:8080/api/v1/workspaces/$ID -H 'Content-Type: application/json' -d '{"name":"Notes"}'
curl -X DELETE localhost:8080/api/v1/workspaces/$ID
```

The event stream sends one `status` event (`{"id": "…", "status": "READY"}`) per existing workspace on connect, then
one per change, plus a `deleted` event (`{"id": "…"}`) after each deletion; a status may repeat. A `keepalive` comment follows every 30 seconds.

In the cluster each workspace runs on a Hetzner VM of its own, which the API asks the [VM manager](vm-manager.md) for:
creating a workspace stores it, then creates its VM, and removes both again if that fails, so a failed create leaves
nothing billing. `serverType` is optional and defaults to the VM manager's default size. Deleting the workspace deletes
its VM, disk included, and the call answers only once the VM manager has; if it cannot, the workspace is kept and the
call fails. Statuses come from the VM manager's VMs and its event stream. The key goes to the VM manager as the VM's
system secret and the rest of the harness environment (base URL, harness password, …) as its system environment, which
the VM's supervisor starts the harness with; restarting marks the VM `RESTARTING`, and its supervisor recreates the
harness with fresh values. Every call to the harness goes through the agent hub's tunnel to the VM, at
`WORKSPACED_AGENT_HUB_URL` + `/internal/vms/<vm id>/harness`; the VM's supervisor sets the harness password, so the
API holds none. There is no other runtime: a local run without
`WORKSPACED_VM_MANAGER_URL` cannot create a workspace (`503`).

### Workspace terminals

Terminal sessions are OpenCode V2 PTYs running `/bin/bash -l` in `/home/workspace/project`.
They are separate from AI conversations. OpenCode owns their processes and metadata; no database row is needed.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `GET` | `/api/v1/workspaces/{id}/terminals` | | `200`, running and exited terminals |
| `POST` | `/api/v1/workspaces/{id}/terminals` | `{"title":"Terminal 1"}` | `201`, the created terminal and a Location header |
| `GET` | `/api/v1/workspaces/{id}/terminals/{tid}` | | `200`, terminal metadata; `404` if absent |
| `DELETE` | `/api/v1/workspaces/{id}/terminals/{tid}` | | `204`, stops and removes the terminal; already absent is accepted |

Titles must be nonblank and at most 100 characters. Terminal IDs begin with `pty` and contain only letters,
digits, `_`, and `-`. Metadata includes `id`, `title`, `command`, `cwd`, `status` (`running` or `exited`),
`pid`, and nullable `exitCode`. Missing workspaces return `404`; an unavailable harness returns `503`.

`WS /api/v1/workspaces/{id}/terminals/{tid}/connect` attaches to an existing running terminal.
Browser input and shell output use binary frames. Resizing uses a text frame `resize:<columns>:<rows>`
with dimensions from 1 to 1000. Leaving the page, switching terminals, or losing the browser connection
only detaches; the shell keeps running. Reconnecting attaches to that same PTY, with OpenCode replaying
retained output. Explicit deletion terminates the shell and its commands, retaining workspace files and AI conversations.
Exited terminals remain listed until deleted. Processes do not survive a workspace runtime restart.
An invalid, missing, exited, or unavailable terminal closes the connection with an error reason.
The old auto-creating `/terminal` WebSocket has been replaced by these explicit lifecycle routes.

The frontend polls metadata every five seconds while visible, supports searching a scrollable terminal list,
and addresses the selected terminal at `/workspaces/{id}/terminal/{tid}` so reload and browser history reconnect correctly.
The local runtime supports terminals when a V2 harness is configured (as elsewhere, all local workspaces share that configured harness).

Terminal connections use short-lived OpenCode WebSocket tickets; pod credentials stay in the backend.
The API no longer needs `pods/exec`. Persistent homes require these additional service-account permissions:

```yaml
- apiGroups: [""]
  resources: ["persistentvolumeclaims"]
  verbs: ["get", "list", "create", "delete"]
```

## Sessions

Each workspace pod runs the agent harness, [opencode](https://opencode.ai) (`apps/harness`), as `opencode serve`.
A session is one OpenCode V2 session working in the shared directory `/home/workspace/project`. The database
holds the session's id, that path and opencode's id for it; the conversation itself lives in the harness.
A session belongs to the user who started it: in a shared workspace nobody else lists it, and its routes answer them
`404`. A scheduled task's runs belong to the workspace and are listed to everyone on it.

| Method   | Path                                                    | Body                 | Answers                                 |
|----------|---------------------------------------------------------|----------------------|-----------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/sessions`                      |                      | `200` and the sessions, oldest first    |
| `POST`   | `/api/v1/workspaces/{id}/sessions`                      |                      | `201` and the session                   |
| `DELETE` | `/api/v1/workspaces/{id}/sessions/{sid}`                |                      | `204`; shared project files remain     |
| `GET`    | `/api/v1/workspaces/{id}/sessions/{sid}/messages`       |                      | `200` and the conversation              |
| `POST`   | `/api/v1/workspaces/{id}/sessions/{sid}/messages`       | `{"text": "…"}`      | `202`; the reply streams on `/events`   |
| `POST`   | `/api/v1/workspaces/{id}/sessions/{sid}/abort`          |                      | `204`                                   |
| `GET`    | `/api/v1/workspaces/{id}/sessions/{sid}/events`         |                      | `200` and a server-sent event stream    |

The stream carries that one session only: `message` (`{id, role}`), `part` (`{messageId, part}`, a part's whole
state, which replaces any earlier one of that id), `delta` (`{messageId, partId, delta}`, text appended to a part),
`status` (`{busy}`), `title` (`{title}`, also stored) and `error` (`{message}`). A part is `text`, `reasoning` or a
`tool` with its name and status. A session's `title` is null until the agent names it after the first exchange, using
the workspace's own model; a title given while no chat was open is picked up the next time the sessions are listed.
Each session also carries its `createdAt` and `available` flag. Listing reconciles stored rows against OpenCode's
cursor-paginated root session API; missing conversations remain indexed with `available: false`.
Messages are fetched through V2 cursor pages in chronological order. A workspace shares one upstream event stream
between browser subscribers. V2 events are volatile, so clients reload history on reconnect and after execution
ends. A `refresh` event also requests a history reload when an inbox message is delivered.

### Session agents

`GET /api/v1/workspaces/{id}/sessions/{sid}/agent` returns `{current, agents}`. `current` is OpenCode's
selected agent ID, or null while unset. Each option includes `id`, `name`, `description`, and `mode`.
Discovery uses the session's own location; hidden agents and agents restricted to `subagent` mode are excluded.

`PUT /api/v1/workspaces/{id}/sessions/{sid}/agent` accepts `{"agent":"plan"}` and returns the confirmed
selection. It maps to OpenCode's `POST /api/session/{sessionID}/agent`, preserving conversation history,
workspace model preference, and the session's model. Unknown, hidden, and subagent-only choices return `400`;
a session outside the selected workspace returns `404`; an unavailable harness or refused switch returns `503`.

The session composer's agent picker sits beside the model picker and reads the selection from OpenCode.
Sending is disabled until a switch is acknowledged. Agent switching is disabled during an active reply or
while a form awaits an answer. Failed or uncertain switches can be reconciled with **Reload agents**.

The API reaches the harness through the workspace's Service, at
`http://workspaced-ai-workspace-<id>.<namespace>.svc:4096`, with HTTP basic auth; the password is generated when the
workspace is created and kept in its Secret as `OPENCODE_PASSWORD`, which no user secret may replace. Existing
`OPENCODE_SERVER_PASSWORD` secrets are accepted by V2 and by endpoint discovery. A
harness that is not running, not answering, or belongs to a workspace made before the harness existed answers `503`.
opencode is limited to the `openrouter` provider, reading the workspace's `OPENROUTER_API_KEY` and
`OPENROUTER_BASE_URL`; its default model is set in `apps/harness/opencode/opencode.json`.

### Editing chat messages

`PUT /api/v1/workspaces/{id}/sessions/{sid}/messages/{messageId}` accepts
`{"text":"Updated prompt"}` and returns `202` once a new reply is started. Only user messages can be edited.
The harness must be idle: the edited message and all later conversation turns are replaced, while project files
are retained. The replacement uses the workspace's currently selected model. Invalid text or an assistant message
returns `400`, unknown sessions/messages return `404`, and a harness failure returns `503`. A failure after the
history was truncated can leave the conversation shortened; reload its history before retrying.

The model selector is available beside the composer for new tasks and session chats. It continues to apply to
all sessions in the workspace. Supported browsers also offer microphone dictation; recognized text stays in the
composer for review and is sent as an ordinary text prompt.

### Session files

| Method | Path | Body | Answers |
|--------|------|------|---------|
| `PUT`  | `/api/v1/workspaces/{id}/sessions/{sid}/files/{name}` | the file itself | `201` and `{name, path, size}`; `400`, `411`, `413` |
| `POST` | `/api/v1/workspaces/{id}/sessions/{sid}/files/links` | `{"path": "outbox/report.pdf"}` | `200` and `{url, expiresAt}`; `400`, `404` |
| `GET`  | `/api/v1/files/{token}` | | `200` and the file as an attachment; `404` |

An upload, at most 100 MB with its `Content-Length`, is streamed into `uploads/<name>` in the session's own folder and
never held by the API; the UI names the uploaded files under the message it sends. A name is a plain file name: up to
200 letters, digits, spaces and `. _ ( ) + -`, starting with a letter or digit.

The agent hands a file back by saving it in the session's folder and linking it relatively, as
`[report.pdf](outbox/report.pdf)` (the system guide tells it so); the chat renders such a link as a download button.
A link is signed with the login key under its own label, so the download needs no sign-in, and it lasts 15 minutes. It
is checked against the session on every use, so it dies with the session, and it is always served as an attachment,
so a file the agent wrote never renders as a page of this site. A path that would leave the session's folder is `400`.

## Agent settings

| Method   | Path                                           | Body                                              | Answers                                   |
|----------|------------------------------------------------|---------------------------------------------------|-------------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/models`               |                                                   | `200` and the catalogue, or `503`         |
| `PUT`    | `/api/v1/workspaces/{id}/model`                | `{"model": "anthropic/claude-sonnet-5.5"}` or `{"model": null}` | `200`, or `400` for an unknown model |
| `GET`    | `/api/v1/workspaces/{id}/mcp-servers`          |                                                   | `200` and the servers with their status   |
| `POST`   | `/api/v1/workspaces/{id}/mcp-servers`          | `{"name": "github", "url": "https://…", "headers": {"Authorization": "Bearer {env:GITHUB_TOKEN}"}}` | `201`, `400` or `409` |
| `DELETE` | `/api/v1/workspaces/{id}/mcp-servers/{name}`   |                                                   | `204` or `404`                            |

**Model.** The catalogue is the harness's own list of the provider's models that can call tools, which an agent
needs, with `defaultModel` — the one the harness's configuration names. A workspace's choice is stored in its row
and sent with every prompt, so it applies from the next message in every session with nothing reloaded; `null`
returns to the default. A model the catalogue does not list is refused with `400`, so a typo fails here rather than
as a broken reply.

**MCP servers.** OpenCode is the source of truth. The API lists all effective servers through
`GET /api/mcp`, including local servers and servers configured outside the UI. It adds remote servers
through `PUT /api/experimental/mcp/{server}` and removes servers through the corresponding `DELETE`.
Responses contain names and connection statuses; OpenCode's list endpoint does not expose URLs or headers.
No project configuration files are read or rewritten, and no location reload is needed.

These mutation endpoints change runtime state only. UI additions disappear on restart, and removals of
servers from OpenCode's own configuration last until restart. For durable setup, configure servers in
OpenCode's configuration files on the workspace PVC.

Header values must be `{env:SECRET_NAME}`, optionally prefixed with `Bearer ` or `Basic `.
Store actual credentials in Kubernetes Secrets; header values are never returned by the API.
A newly added environment secret requires a workspace restart before OpenCode can read it. MCP operations
require a reachable workspace, so they return 503 while OpenCode is unavailable.

PostgreSQL holds no MCP settings or references. Migration V6 drops the old MCP table without importing its entries.

## Instructions

Every session's agent is given, through OpenCode's `instructions` config in the harness image:

1. **The system guide**, `/usr/share/harness/instructions/system.md`, read-only in the image (source:
   `apps/harness/instructions/system.md`). It tells the agent that this is Alpine with musl and BusyBox, which tools it
   has, that only `/home/workspace` survives a restart, how to install more so it lasts, and where MCP servers go
   (`opencode.jsonc`, never the `opencode.json` the image replaces on every start). Nobody can edit it, and the app does
   not show it.
2. **The workspace's instructions**: every `*.md` in `~/.config/workspaced/instructions/`, in the persistent home. A new
   workspace starts with the image's defaults (`apps/harness/instructions/defaults/`), copied by `start.sh` on the first
   start only. From then on the folder is the user's: they edit it in the app, and the agent may edit it too. Nothing is
   kept in the database; the files are the only copy.

| Method   | Path                                            | Body              | Answers                                     |
|----------|-------------------------------------------------|-------------------|---------------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/instructions`          |                   | `[{name, content}]` in name order           |
| `PUT`    | `/api/v1/workspaces/{id}/instructions/{name}`   | `{"content": …}`  | `{name, content}`; creates or replaces      |
| `DELETE` | `/api/v1/workspaces/{id}/instructions/{name}`   |                   | `204`, or `404` for no such file            |

A name is the file's without `.md`: up to 63 letters, digits, `-` and `_`, starting with a letter or digit, else `400`;
content is Markdown of at most 100,000 characters. All three read or write the workspace itself, so a workspace that is
not running answers `503`.

When the agent sees a change was measured against OpenCode 2.2.5 by recording the requests it sends the model:

| Change                | New sessions | Sessions already open                 |
|-----------------------|--------------|---------------------------------------|
| A file edited         | yes          | from their next message               |
| A file deleted        | yes          | from their next message               |
| A file added          | yes          | only once the workspace restarts      |

Each session has a folder of its own, so a new session always sees every file. OpenCode settles which files there are
when a session's folder first loads; its reload does not change that, so the API never reloads it for instructions.

The harness image carries Python, `uv`/`uvx`, `jq`, `yq`, `make`, an SSH client, SQLite and the usual file tools
besides Node.js and git. Anything else installs into the persistent home, without root:

| Command | Where it installs | How it is found |
|---------|-------------------|-----------------|
| `apk add <package>` | `~/.local/alpine`, its own Alpine root (`apps/harness/apk.sh` wraps `/sbin/apk` with `--root`, a user-mode database and `--no-scripts`) | `PATH` and the musl loader's path (`/etc/ld-musl-x86_64.path`) reach into it, after the image's own folders, so the image's tools are never shadowed |
| `npm install -g` | `~/.local` (`NPM_CONFIG_PREFIX`) | `~/.local/bin` on `PATH` |
| `pip install` | `~/.local` (`PIP_USER`, `PYTHONUSERBASE`) | `~/.local/bin` on `PATH` |
| `uv tool install`, `uvx` | `~/.local/share/uv`, commands in `~/.local/bin` (`UV_TOOL_BIN_DIR`) | `~/.local/bin` on `PATH` |

Package install scripts cannot run without root, so a package that relies on one may leave its command under
`~/.local/alpine/usr/libexec` (PostgreSQL's `psql`, for one); the guide tells the agent to link it into `~/.local/bin`.

Workspaces keep the harness image they were created with. **Restart** moves a workspace on an older image to the
current one, keeping its home; that is how existing workspaces pick up a new harness, the guide included.

## Recipes

A recipe is Markdown instructions an agent follows to set something up in a workspace, shared in the app's store. Its
author is whoever created it; `PUBLIC` recipes are listed for everyone, who can rate them, and `PRIVATE` ones are seen by
their author alone. Someone else's private recipe answers `404`, exactly as one that does not exist. Applying one is the
frontend's job: it starts a session in the chosen workspace and sends the recipe as its first message.

| Method   | Path                              | Body                     | Answers                                                    |
|----------|-----------------------------------|--------------------------|------------------------------------------------------------|
| `GET`    | `/api/v1/recipes`                 |                          | `200` and a page of recipes, without their Markdown        |
| `GET`    | `/api/v1/recipes/{id}`            |                          | `200` and the recipe with its `content`, or `404`          |
| `POST`   | `/api/v1/recipes`                 | the recipe's fields      | `201`, the recipe, and its URL in `Location`               |
| `DELETE` | `/api/v1/recipes/{id}`            |                          | `204`; `403` unless the author or an admin                 |
| `PUT`    | `/api/v1/recipes/{id}/rating`     | `{"stars": 1–5}`         | `200` and `{average, count, myRating}`; `409` for one's own |
| `DELETE` | `/api/v1/recipes/{id}/rating`     |                          | `204`, rated or not                                        |

The fields are `title` (1–100 characters), `summary` (up to 300, may be empty), `category` (`apps`, `websites`,
`automation`, `data`, `ai`, `devops` or `other`), `tags` (up to 8, each 1–30 lowercase letters, digits and `-`; trimmed,
lowercased and deduplicated), `visibility` and `content` (Markdown, 1–100,000 characters); anything else is a `400`.
A recipe never changes once created, so its ratings always refer to what was published; to change one, its author
publishes a new one and deletes the old. Each recipe answers with its `author` (`id`, and the `firstName` and `lastName` the
author had when they created it, `Unknown` and empty when they had none; never the email), `createdAt`, `publishedAt`
(when it was created public, null for a private one), `rating` (`average` to one decimal, `count`), the
caller's own `myRating` or null, and `mine`.

The list takes `scope` (`public`, the default, for every public recipe, the caller's included; `private` for the
caller's own private ones), `q` (matches title, summary or a tag, ignoring case), `category`, `sort` (`top`, the default:
average rating, then number of ratings; `newest`: when each turned public), `page` and
`size` (default 24, at most 100), and answers Spring Data's paged model, `{"content": […], "page": {…}}`.

### Recipe drafts

The agent writes a recipe it is asked for as a draft in the workspace, `~/.config/workspaced/recipes/<name>.md`: front
matter (`title`, `summary`, `category`, `tags`, `visibility`, and `recipe`, the id of the recipe it revises) and then
the Markdown. The harness never calls the API, so a draft reaches the store only when the user imports and publishes it;
to have the agent revise a published recipe, the app writes it here first. Importing a revision publishes a new recipe,
and `recipe` only tells the app which of the user's recipes to offer to delete.

| Method   | Path                                           | Body             | Answers                                |
|----------|------------------------------------------------|------------------|----------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/recipe-drafts`        |                  | `[{name, content}]` in name order      |
| `PUT`    | `/api/v1/workspaces/{id}/recipe-drafts/{name}` | `{"content": …}` | `{name, content}`; creates or replaces |
| `DELETE` | `/api/v1/workspaces/{id}/recipe-drafts/{name}` |                  | `204`, or `404` for no such draft      |

Names and sizes are as for instructions, and a workspace that is not running answers `503`.

## Workspace collaborators

Anyone on a workspace invites others by email. The invitation's link (`/invitation#<code>`) works once, for 24 hours,
for whoever signs in — with an existing account or a new one — as the address it was sent to. A free demo cannot be
shared (`409`) until it is kept as a paid workspace. A collaborator may do
everything but delete the workspace or keep a demo (`403`), both being the owner's money, and the workspace keeps
running on the owner's AI connector, which only the owner can change. Only the owner removes collaborators or withdraws
invitations; a collaborator may remove themselves, which is leaving. A workspace has at most 5 collaborators besides its
owner, and an open invitation holds one of those seats. An invitee who already has an account also gets an in-app
notification whose action, `/invitations/{id}`, accepts by id (`POST /api/v1/invitations/{id}/accept`); the signed-in,
Auth0-verified address is what admits them, exactly as with the mailed code. Each user names a workspace for themselves:
the owner's rename changes `workspace.name`, a collaborator's changes their own `workspace_collaborator.name`, which
starts as the workspace's name when they join; neither reaches anyone else. Losing a workspace, by removal or deletion, closes
that user's terminal WebSockets and session event streams on every instance.

Sessions are listed per user, but that is a view, not a privacy boundary: everyone on a workspace shares its VM, its
terminals, its secrets and the harness's own session store. Treat a collaborator as fully trusted on the VM.

The database keeps no address: a collaborator is a user id, shown with the email Auth0 has for them, and an invitation
holds only an HMAC of the address (keyed with `WORKSPACED_JWT_SECRET`) and a SHA-256 of its code. An open invitation is
therefore listed without an address. Expired invitations are deleted hourly. The mail goes from the no-reply sender,
at most 50 a day per inviter, counted in `invitation_mail` so that withdrawing an invitation does not free the allowance.
Addresses are read from Auth0 through a ten-minute in-memory cache; when Auth0 fails, the list shows the rest without
them rather than failing.

| Method   | Path                                                   | Body               | Answers                                                         |
|----------|--------------------------------------------------------|--------------------|-----------------------------------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/collaborators`                |                    | `200`: the owner, then `APPLIED` collaborators, then `PENDING` invitations |
| `POST`   | `/api/v1/workspaces/{id}/invitations`                  | `{"email": "…"}`   | `201`; `409` already on it, or its 5 seats (collaborators plus open invitations) are taken; `429` same address within a minute, or 50 sent by the caller in 24 hours |
| `DELETE` | `/api/v1/workspaces/{id}/invitations/{invitationId}`   |                    | `204`; `403` unless the owner                                   |
| `DELETE` | `/api/v1/workspaces/{id}/collaborators/{userId}`       |                    | `204`; `403` unless the owner or that user; `409` for the owner |
| `POST`   | `/api/v1/invitations/{invitationId}/accept`            |                    | as below, by id, from the in-app notification |
| `POST`   | `/api/v1/invitations/accept`                           | `{"code": "…"}`    | `200` `{"workspaceId"}`; `403` another address; `404` expired or used; `409` already 5 collaborators |

## Workspace secrets

Environment variables a workspace keeps secret. The database holds their names only; each value goes to the VM
manager, which keeps it in Vault beside the provider key (see [vm-manager.md](vm-manager.md#vm-secrets)), and is never
returned. The provider key is listed first, marked `managed`, and is not a database row at all. A workspace holds at
most 50 besides it; the API counts before it calls the VM manager, which holds the same limit.

| Method   | Path                                     | Body                                   | Answers                                    |
|----------|------------------------------------------|----------------------------------------|--------------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/secrets`        |                                        | `200` and the names, or `404`              |
| `POST`   | `/api/v1/workspaces/{id}/secrets`        | `{"name": "GITHUB_TOKEN", "value": "…"}` | `201` and the secret, `404`, or `409`      |
| `DELETE` | `/api/v1/workspaces/{id}/secrets/{name}` |                                        | `204`, `404`, or `409`                     |

A name must be a valid environment variable name (`[A-Za-z_][A-Za-z0-9_]*`, at most 100 characters), else `400`.
`409` means the workspace already has that secret, holds 50 already, has no VM yet, or its provider or harness sets it (`OPENROUTER_API_KEY`,
`OPENROUTER_BASE_URL`, `OPENCODE_SERVER_PASSWORD`, `OPENCODE_SERVER_USERNAME`), which no user secret may shadow or
remove. A pod reads its environment once, when it starts,
so a running workspace sees a change only after it restarts.

## Workspace time zone

Every workspace has an IANA time zone, which its harness always starts with as `USER_TIMEZONE`. It is set when the
workspace is created (`timeZone` in the create request, the browser's zone from the web app, UTC when omitted) and
changed with `PUT /api/v1/workspaces/{id}/time-zone` and `{"timeZone": "Europe/Sofia"}`, which answers the workspace.
Only region names are accepted, never an offset, so daylight saving keeps working. The VM manager keeps it in the VM's
system environment, so neither a secret nor a variable of that name can override it; both are refused. Because the
harness reads it only at start, a change sets `restartRequired`, as a secret or variable change does, until the
workspace restarts.

## Workspace firewall

What the internet may reach on a workspace's VM. The rules are kept only by the VM manager, on the VM's own Hetzner
firewall, so the API proxies them under a per-workspace lock rather than storing a copy. A new VM has none, so nothing
reaches it; whatever the workspace publishes on an opened port answers at the VM's address, over plain HTTP unless the
workspace sets up TLS itself. `409` is a workspace whose VM does not exist yet.

| Method | Path                                     | Body                                                                | Answers                                          |
|--------|------------------------------------------|---------------------------------------------------------------------|--------------------------------------------------|
| `GET`  | `/api/v1/workspaces/{id}/firewall`       |                                                                     | `200` with the address and rules, `404` or `409` |
| `PUT`  | `/api/v1/workspaces/{id}/firewall`       | `{"rules": [{"protocol": "tcp", "port": "8080", "sourceIps": []}]}` | `200`, `400`, `404` or `409`                     |
| `PUT`  | `/api/v1/workspaces/{id}/firewall/agent` | `{"allowed": true}`                                                 | `204`, `400` or `404`                            |

`allowed` is on for a new workspace; the agent still asks in the chat before it opens a port. With it on, the agent's `firewall` tool writes one request line to `~/.config/workspaced/firewall/request` —
`list`, `open <port>[/tcp|udp] [<source>]`, `open icmp [<source>]`, `close <port>[/tcp|udp]` or `close icmp` — and the
API, polling every few seconds and only in workspaces that allow it, carries it out and writes the address and the
resulting rules, or `error: <reason>`, to `result` beside it. Opening a port it already has replaces that rule.

## Workspace metrics

| Method | Path                              | Answers                                                       |
|--------|-----------------------------------|---------------------------------------------------------------|
| `GET`  | `/api/v1/workspaces/{id}/metrics` | `200` and the metrics, `400`, `404`, `409`, `502`, or `503`   |

`?range=` is `live` (the last 15 minutes, the default), `1h`, `24h`, `7d` or `30d`; anything else answers `400`. CPU,
disk and network come from the VM manager, which reads them from Hetzner; memory is the VM's current reading only,
`null` once it has not reported for a while, since Hetzner keeps no memory series. `409` means the workspace has no VM
yet.

## Billing

| Method | Path                                     | Answers                                                 |
|--------|------------------------------------------|---------------------------------------------------------|
| `GET`  | `/api/v1/billing`                        | `200`, the balance and top-ups newest first             |
| `POST` | `/api/v1/billing/top-ups`                | `201` and a Stripe Checkout URL, `400`, `502`, or `503` |
| `POST` | `/api/v1/billing/top-ups/{id}/refresh`   | `200` and the top-up, or `404`                          |
| `GET`  | `/api/v1/billing/top-ups/{id}/invoice`   | `303` to Stripe's invoice PDF, or `404`                 |
| `POST`  | `/api/v1/billing/card`                   | `201` and a Stripe Checkout URL that saves a card, `502`, or `503` |
| `POST`  | `/api/v1/billing/card/confirm`           | `204`, `404`, or `409` until Stripe has saved it |
| `DELETE` | `/api/v1/billing/card`                   | `204`; automatic top-ups turn off |
| `PUT`   | `/api/v1/billing/auto-top-up`            | `200` and the settings, `400`, or `409` without a card |
| `POST` | `/api/v1/billing/stripe/webhook`         | Stripe only: `200`, or `400` for a bad signature        |

A top-up is credited only once Stripe reports its Checkout Session paid; the webhook and the browser returning to
`/billing?top_up=<id>` both make the API read the session back rather than trust the caller, and a unique ledger entry
per top-up keeps the two from crediting it twice. Stripe issues the invoice (`invoice_creation`), so the API keeps only
its id and number. VAT is charged on top at `WORKSPACED_BILLING_VAT_PERCENT` (20 by default, returned as `vatPercent`):
the API finds or creates an exclusive Stripe Tax Rate named `VAT` at that percentage and attaches it to the line item, so
the invoice shows it as tax. The balance is credited the amount before VAT, and a session whose subtotal or total differs
from what was asked is not credited.
Amounts are €5.00 to €1,000.00, in euros, the currency the Stripe account settles in. The balance lives in `billing_account` in micro-units, so later
usage charges can be fractions of a cent, and every change to it is a row in `billing_ledger_entry`.
`WORKSPACED_STRIPE_SECRET_KEY` (a restricted key: write on Customers, Checkout Sessions, Tax Rates, Invoices, Invoice
Items and Payment Methods, read on Setup Intents) and
`WORKSPACED_STRIPE_WEBHOOK_SECRET` have no default; without the key a top-up answers `503`, and
`WORKSPACED_BILLING_RETURN_URL` is the page Checkout returns to. The webhook subscribes to
`checkout.session.completed`, `.async_payment_succeeded`, `.async_payment_failed` and `.expired`.

A user can save a card through a Checkout Session in setup mode, which charges nothing; the browser comes back with
`?card_session=<id>`, and the API keeps the card from that or from the session's `checkout.session.completed` event,
detaching any card it replaces. With automatic top-ups on, the charge run after each round of hourly charges charges
the card `amountCents`, plus VAT, whenever the balance is below `belowCents`: it creates an invoice with that one line,
finalizes it and pays it off session, so Stripe issues an invoice as it does for a checkout, and the top-up is listed
with `automatic` set. Every step is keyed by the top-up's id, so one Stripe could not be reached for stays pending and
is finished on the next run without charging twice. A declined card voids the invoice, marks the top-up `FAILED`,
turns automatic top-ups off and notifies the user. An admin's card is never charged.

A user who is not an admin needs a balance covering `WORKSPACED_BILLING_REQUIRE_COVERED` (7 days) of every workspace
they have plus the new one to create one, and each workspace is then charged by the hour as the hour starts. An admin's workspaces are never charged, even
those made before the user became one. When the balance cannot pay a workspace's next hour, its VM is shut down and it
is `PAUSED`: it is not charged, nor can it be restarted. Once the balance pays `WORKSPACED_BILLING_REACTIVATION_FEE`
(5.00 by default, returned as `reactivationFeeCents`) and an hour, it starts again within the charge interval, the fee is
taken, and it is charged from then on. A workspace still paused after `WORKSPACED_BILLING_PAUSED_FOR` (`3d` by default, returned as `pausedForHours`)
is deleted. The owner is notified when it is paused, each day it stays paused, when it starts again and when it is
deleted, and each day in the week before the balance runs out. `GET /billing` also returns what the running
workspaces cost a month, when the balance stops paying for them (`fundedUntil`), each workspace's cost with when a
paused one is deleted, and what was charged each of the last 30 days.

The schema is created by Flyway from `apps/api/src/main/resources/db/migration` when the API starts.

## Running locally

The API needs PostgreSQL; `WORKSPACED_DB_HOST`, `_PORT`, `_NAME` and `_USERNAME` default to `localhost`, `5432`,
`workspaced` and `workspaced`, and `WORKSPACED_DB_PASSWORD` has no default. The tests start their own database in
Docker, so `verify` needs a running Docker daemon.

```sh
docker run -d --name workspaced-db -p 5432:5432 \
  -e POSTGRES_DB=workspaced -e POSTGRES_USER=workspaced -e POSTGRES_PASSWORD=workspaced postgres:18-alpine
cd apps/api
# Sign in through the web app on :5173 as the workspaced-ai-dev Auth0 application, never the
# production one: it allows only localhost and has no Management API grant, so saving names and
# recipe authors' names do not work locally.
WORKSPACED_DB_PASSWORD=workspaced WORKSPACED_JWT_SECRET=local-dev-signing-key-at-least-32-bytes \
  WORKSPACED_AUTH_ISSUER=https://auth.workspaced.online/ \
  WORKSPACED_AUTH_CLIENT_ID=ht8eqs4n2xyILL6Yzeo1ToMDHqEHiE97 \
  WORKSPACED_AUTH_MANAGEMENT_API=https://workspaced.eu.auth0.com/api/v2/ \
  WORKSPACED_AUTH_CLIENT_SECRET="$(vault kv get -field=auth0-client-secret secret/workspaced-dev-secrets)" \
  WORKSPACED_APP_URL=http://localhost:5173 WORKSPACED_SITE_URL=http://localhost:5173 \
  mvn spring-boot:run                                     # http://localhost:8080/api/v1/swagger-ui
mvn fmt:format                                            # fixes what the fmt:check gate rejects
mvn verify                                                # tests plus the 80% line-coverage floor
mvn -DskipTests spring-boot:build-image                   # buildpacks image, no Dockerfile

cd apps/web
npm run dev                                               # proxies /api to localhost:8080
npm test                                                  # vitest plus the 80% line-coverage floor
```

`POST /api/v1/workspaces/{id}/tutorial-completed` records completion of the owner's active free
trial and sends one in-app notification, including its scheduled deletion time. It sends no email.
It returns 204 on success (also on retries), 409 for a non-trial, draft or expired trial, and 404
for a missing workspace. Workspace ownership and CSRF protection apply as on other workspace
mutations. A row lock and a per-account deduplication key prevent duplicate notifications.


## Protected Trial AI connectors

Configure `WORKSPACED_OPENROUTER_MANAGEMENT_KEY` on the **API backend only**, using a secret
from your deployment secret store. It is an OpenRouter management key belonging to the platform's
funded OpenRouter account, not an inference key or an Auth0 management token. Never give it to the
web app, a workspace, or the VM manager. Unset configuration disables new trial-key issuance;
existing trial keys remain usable. No key value is returned in connector responses.

After a verified new account commits, the API issues its Trial connector through
`POST https://openrouter.ai/api/v1/keys`, with a fixed `limit` (set in `HttpOpenRouterManagement`),
`limit_reset: null`, and `include_byok_in_limit: true`. This is a **lifetime spending cap per key**, not a recurring
allowance and not a deposit of OpenRouter credits. The platform account must have credits.
The issued inference key is encrypted at rest separately from the backend management credential.
The provider key hash is retained for operational cleanup; no user route can reset, refill,
regenerate or delete the protected key. Database uniqueness and a per-user row lock serialize
signup and retry requests, so successful repeated sign-ins do not mint more grants.

Signup still succeeds during provider outages. Listing connectors retries provisioning, including
for existing accounts without a Trial connector. If database persistence fails after issuance,
the backend attempts to revoke the orphaned key. An ambiguous provider timeout cannot safely
recover the one-time plaintext; operators can identify orphaned grants by their deterministic
`workspaced trial: <user UUID>` labels. Errors never echo credentials or provider response bodies.

Connector metadata includes `trial: true` for the protected default, listed first.
`DELETE /ai-connectors/{id}` returns 409 for it. Deleting a personal connector reassigns every
workspace referencing it to the owner's Trial connector, updates the protected VM secret, and
marks running workspaces as requiring restart. It does not delete the personal key in OpenRouter.
If fallback provisioning or a VM update fails, deletion is refused; changes to already-updated VM
secrets are restored on a best-effort basis. Exhausting Trial does not trigger another grant.

Draft launch accepts exactly one of `apiKey` or `connectorId`; a saved connector is resolved only
for its owner and must match the workspace's provider. The tour launches with the Trial connector
ID. `POST /ai-connectors/{id}/acknowledgement` with `{"accepted":true}` records the provider notice
before the tour or workspace creator uses the pre-provisioned key.

## AI connector budget and expiry notifications

Every saved OpenRouter connector, including Trial and personal keys, is checked for low budget
and expiry. Alerts are in-app only, identify the connector by its user-visible name, and link to
`/settings/ai-connectors` with **Connect your AI account**. The text explains selecting the new key
under workspace Settings → Secrets and restarting the workspace.

- At 50% through 20% remaining: a WARNING notification.
- Below 20%, above zero: an ERROR notification with an urgent icon.
- Zero or negative remaining: an ERROR notification that the named key's budget is used up.
- Confirmed provider expiry: an ERROR notification that the named key expired. A rejected key
  without a previously observed expiry is described as needing attention, not falsely declared
  expired or exhausted.

Each budget threshold is sent once per key and provider budget window. A row lock and the
notification deduplication key prevent concurrent page refreshes and background checks from
sending duplicates. Daily, weekly and monthly budgets use UTC windows (weeks begin Monday);
a spending-cap change also starts a new notification cycle. Skipped thresholds do not generate
multiple warnings at once. Keys without a spending cap or reported remaining amount do not get
invented percentage warnings. Provider outages and rate limits do not imply expiry.

Usage reads check thresholds immediately. A ShedLock-protected background sweep also checks up
to 50 least-recently-checked keys per run, starting after one minute and repeating one minute after
a sweep finishes. Configure `workspaced.openrouter.alert-interval` and `alert-batch-size` to tune
this; batches are capped at 100. Checks use each user's own encrypted inference key, including when
no browser is open, and never return or log credentials. No additional email is sent.

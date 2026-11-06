# API

The whole app is served under the `/api/v1` context path, so the host root is free for the frontend: Swagger UI is at
`/api/v1/swagger-ui`, the OpenAPI 3.0 document at `/api/v1/v3/api-docs`, health at `/api/v1/actuator/health`.

## Authentication and tenancy

Every route needs a signed-in user except `POST /auth/login`, `POST /auth/logout`, health and the API docs. A fresh
database has one account, **admin / admin** (created by `V9__users.sql`); change its password from the settings page or
`PUT /account/password` after the first sign-in.

`POST /auth/login` with `{"username","password"}` answers `{account, token, expiresAt}`. The token is an HS256 JWT signed
by the API (Spring Security's OAuth2 resource server validates it); its subject is the user's id. The same token is set
as the `workspaced_token` cookie — HttpOnly, SameSite=Strict, scoped to `/api/v1`, Secure over HTTPS — which is how the
browser authenticates, EventSource and the terminal WebSocket included, since neither can set a header. Other clients
send `Authorization: Bearer <token>`. A change (any method but GET) sent with that cookie must also carry the CSRF
token, else `403`: `GET /auth/csrf` answers it and sets the HttpOnly `XSRF-TOKEN` cookie it is checked against, and the
change sends it back in `X-XSRF-TOKEN`. Requests without the sign-in cookie, bearer requests included, need none. `POST /auth/logout` clears the cookie; tokens are stateless, so one kept elsewhere
stays valid until it expires. A missing, invalid or expired token answers `401` as a problem detail.

`GET /account` returns the signed-in user (`id`, `username`, `firstName`, `lastName`, `avatar`).
`PUT /account/profile` replaces the names (at most 100 characters, trimmed) and the avatar, a JPG/PNG/WebP/GIF `data:`
URL of at most 2 MB (empty removes it). `PUT /account/password` takes `currentPassword` and `newPassword` (8–72
characters): `204`, or `422` when the current password is wrong.

`GET /users` lists everyone who can sign in, a page at a time, as Spring Data's paged model:
`{"content": [{id, username, firstName, lastName, avatar}], "page": {size, number, totalElements, totalPages}}`. It
takes `page` (from 0), `size` (default 20, at most 100) and `sort` as `field,direction`, repeatable, over `username`,
`firstName`, `lastName` and `createdAt` (default `createdAt,desc`); any other field is a `400`. The id breaks ties, so
pages never overlap. `POST /users` takes `{username, password, firstName, lastName}` (names at most 100 characters,
trimmed; password 8–72) and answers `201`, or `409` when the username is taken. `DELETE /users/{id}` stops and deletes
each of the user's workspaces, then the user: `204`, `404` for no such user, or `409` for the signed-in user, who cannot
delete themselves. Any signed-in user may manage users; there are no roles yet.

The app is multi-tenant by workspace. Each workspace has an owner; `GET /workspaces` and the `/workspaces/events` stream
show only the caller's, and every route under `/workspaces/{id}` — sessions, secrets, settings, terminals — is checked
against the owner before it runs. Another user's workspace answers `404`, exactly as one that does not exist.
Workspaces created before accounts belong to admin.

| Variable                | Default | Meaning                                                                       |
|-------------------------|---------|-------------------------------------------------------------------------------|
| `WORKSPACED_JWT_SECRET` | none    | HS256 signing key, at least 32 bytes, the same on every instance. Required: the API does not start without it. |
| `WORKSPACED_TOKEN_TTL`  | `12h`   | How long a sign-in lasts.                                                     |

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

## Workspaces

A workspace has an `id` (UUID, assigned by the server), a `name` (1–100 characters, surrounding whitespace trimmed),
the `modelProvider` it was created with, and a `status`: `INITIALIZING` while its environment starts, `READY` once it
can be used, `FAILED` if it stopped or is gone. Workspaces are listed in the order they were created.

| Method   | Path                      | Body               | Answers                                         |
|----------|---------------------------|--------------------|-------------------------------------------------|
| `GET`    | `/api/v1/workspaces`      |                    | `200` and every workspace                       |
| `GET`    | `/api/v1/workspaces/events` |                  | `200` and a server-sent event stream            |
| `POST`   | `/api/v1/workspaces`      | `{"name": "Docs", "modelProvider": "openrouter", "apiKey": "sk-or-…"}` | `201`, the workspace, and its URL in `Location` |
| `PUT`    | `/api/v1/workspaces/{id}` | `{"name": "Docs"}` | `200` and the renamed workspace, or `404`       |
| `DELETE` | `/api/v1/workspaces/{id}` |                    | `204` once its pods are gone, or `404`          |
| `POST`   | `/api/v1/workspaces/{id}/restart` |            | `202` while its pod is replaced, `404`, or `409` for a bare pod |

A blank or overlong name, a blank key, an unknown provider, or an `id` that is not a UUID, answers `400`. Every error body is an RFC 9457 problem detail (`application/problem+json`).

Creating a workspace first calls the provider with the key (OpenRouter's `GET /key`). A key the provider refuses
answers `422` and creates nothing; a provider that cannot be reached answers `502`. The key is never stored by the
API: it goes straight into the workspace's Kubernetes Secret.

```sh
curl -X POST localhost:8080/api/v1/workspaces -H 'Content-Type: application/json' \
  -d '{"name":"Docs","modelProvider":"openrouter","apiKey":"sk-or-…"}'
curl localhost:8080/api/v1/workspaces
curl -X PUT localhost:8080/api/v1/workspaces/$ID -H 'Content-Type: application/json' -d '{"name":"Notes"}'
curl -X DELETE localhost:8080/api/v1/workspaces/$ID
```

The event stream sends one `status` event (`{"id": "…", "status": "READY"}`) per existing workspace on connect, then
one per change, plus a `deleted` event (`{"id": "…"}`) after each deletion; a status may repeat. A `keepalive` comment follows every 30 seconds.

In Kubernetes each workspace runs as a one-replica Deployment named `workspaced-ai-workspace-<id>`, labelled
`workspaced-ai/workspace-id=<id>`, in the namespace the API runs in, behind a Service of the same name. The Deployment
replaces a pod that dies, so the workspace is `INITIALIZING` again rather than `FAILED` while it does; it uses the
`Recreate` strategy, so two pods never share one workspace. A Secret and a ConfigMap of the same name and label hold
its environment — the provider key (`OPENROUTER_API_KEY`) plus any secrets added later in the Secret, the provider's
base URL (`OPENROUTER_BASE_URL`) in the ConfigMap — and the pod takes both with `envFrom`. Deleting the workspace
deletes all of them, and the call answers only once its pods are gone (`workspaced.workspace.stop-timeout`, 60
seconds by default); if they are not gone in time, the workspace is kept and the call fails. Adding or removing a secret
sets the workspace's `restartRequired` (stored as `secrets_pending_restart`), since a running pod reads its secrets
only when it starts; restarting clears it. Restarting deletes the
workspace's pods and lets the Deployment bring up a new one on the same PVC, so sessions, files and secrets survive while
processes and terminals do not; the event stream then reports `INITIALIZING` and `READY`. A workspace made before
Deployments ran as a bare pod of that name, which is still reported on and deleted. Outside
Kubernetes nothing is started and every workspace is `READY` at once, so a local run never acts on whatever cluster
the kubeconfig points at.

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

A workspace's home is a ReadWriteOnce PVC, so project files and OpenCode's conversation database survive replacement
pods. Configure `workspaced.workspace.storage-size` (default `10Gi`) and `storage-class` (blank uses the cluster
default). Deleting a workspace also deletes its PVC; storage reclamation follows the storage class policy.

To chat outside Kubernetes, run a harness and point the API at it:

```sh
docker build --platform linux/amd64 -t harness apps/harness
docker run --rm -p 4096:4096 -e OPENCODE_PASSWORD=dev \
  -e OPENROUTER_API_KEY=sk-or-… -e OPENROUTER_BASE_URL=https://openrouter.ai/api/v1 harness
WORKSPACED_WORKSPACE_LOCAL_HARNESS_URL=http://localhost:4096 \
WORKSPACED_WORKSPACE_LOCAL_HARNESS_PASSWORD=dev WORKSPACED_DB_PASSWORD=workspaced mvn spring-boot:run
```

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

## Workspace secrets

Environment variables a workspace keeps secret. The database holds their names only; each value lives in the
workspace's Kubernetes Secret and is never returned. The provider key is listed first, marked `managed`, and is not
a database row at all.

| Method   | Path                                     | Body                                   | Answers                                    |
|----------|------------------------------------------|----------------------------------------|--------------------------------------------|
| `GET`    | `/api/v1/workspaces/{id}/secrets`        |                                        | `200` and the names, or `404`              |
| `POST`   | `/api/v1/workspaces/{id}/secrets`        | `{"name": "GITHUB_TOKEN", "value": "…"}` | `201` and the secret, `404`, or `409`      |
| `DELETE` | `/api/v1/workspaces/{id}/secrets/{name}` |                                        | `204`, `404`, or `409`                     |

A name must be a valid environment variable name (`[A-Za-z_][A-Za-z0-9_]*`, at most 100 characters), else `400`.
`409` means the workspace already has that secret, or its provider or harness sets it (`OPENROUTER_API_KEY`,
`OPENROUTER_BASE_URL`, `OPENCODE_SERVER_PASSWORD`, `OPENCODE_SERVER_USERNAME`), which no user secret may shadow or
remove. A pod reads its environment once, when it starts,
so a running workspace sees a change only after it restarts.

The schema is created by Flyway from `apps/api/src/main/resources/db/migration` when the API starts.

## Running locally

The API needs PostgreSQL; `WORKSPACED_DB_HOST`, `_PORT`, `_NAME` and `_USERNAME` default to `localhost`, `5432`,
`workspaced` and `workspaced`, and `WORKSPACED_DB_PASSWORD` has no default. The tests start their own database in
Docker, so `verify` needs a running Docker daemon.

```sh
docker run -d --name workspaced-db -p 5432:5432 \
  -e POSTGRES_DB=workspaced -e POSTGRES_USER=workspaced -e POSTGRES_PASSWORD=workspaced postgres:18-alpine
cd apps/api
WORKSPACED_DB_PASSWORD=workspaced WORKSPACED_JWT_SECRET=local-dev-signing-key-at-least-32-bytes \
  mvn spring-boot:run                                     # http://localhost:8080/api/v1/swagger-ui, admin/admin
mvn fmt:format                                            # fixes what the fmt:check gate rejects
mvn verify                                                # tests plus the 80% line-coverage floor
mvn -DskipTests spring-boot:build-image                   # buildpacks image, no Dockerfile

cd apps/web
npm run dev                                               # proxies /api to localhost:8080
npm test                                                  # vitest plus the 80% line-coverage floor
```

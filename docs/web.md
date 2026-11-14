# Web app

React + Tailwind on Vite, in `apps/web`. Components follow atomic design; `test/architecture.test.ts` fails the build
when a rule below is broken.

```
src/
├── api/            fetch calls and their types — imported only by hooks/
├── hooks/          state and side effects (route, workspaces, sessions, chat, VM sizes, model settings, MCP servers,
│                   secrets, users, theme, voice dictation, popover dismissal)
├── lib/            framework-free helpers (routes, relative times, token and price formatting, storage)
└── components/
    ├── atoms/      Logo, Button, IconButton, TextField, ChoiceCard, Spinner, Avatar, Divider, Stars, Tag, CategoryTile — props in, markup out, no hooks
    ├── molecules/  ChatComposer, ChatMessageView, EmptyState, ErrorBanner, Markdown, Menu, Modal, PageHeader,
    │               SecretItem, SessionCard, SettingsSection, ThemeSwitch, UserItem, WorkspaceItem, WorkspaceStatusIcon
    ├── organisms/  TopBar, UserMenu, Sidebar, CreateWorkspaceDialog, SessionList, ChatPanel, ChatModelPicker, ModelPanel, McpPanel,
    │               SecretsPanel, UsersPanel, CreateUserDialog, DeleteUserDialog
    ├── templates/  AppLayout — the frame every page sits in
    └── pages/      WorkspacesPage (the shell: sidebar, dialogs, routing), WorkspacePage, SessionPage, SettingsPage,
                    TerminalPage
```

| Rule                                               | Why                                                        |
|----------------------------------------------------|------------------------------------------------------------|
| A layer imports only from the layers below it      | A change to an atom can never depend on a page             |
| Atoms use no hooks                                 | They stay reusable anywhere and trivial to test            |
| Only `hooks/` imports `api/`                       | Every request has one owner, and components stay mockable |
| Each layer exports through its `index.ts`          | Moving a file inside a layer changes no imports elsewhere  |

The app first asks the API who is signed in (`GET /api/v1/account`) and shows a sign-in page until someone is; a fresh
install accepts admin/admin. The session itself is an HttpOnly cookie the code never reads. Any `401` from the API
returns the app to the sign-in page, and Logout in the account menu clears the cookie. The profile on the settings page
(names and avatar) and the password change are saved to the account. Its Users tab lists everyone who can sign in,
sorted as chosen (newest first by default) and loaded 20 at a time as the end of the list scrolls into view; it adds a
user in a dialog and deletes anyone but yourself after a confirmation dialog. A workspace's settings have an Instructions section:
the workspace's instruction files, which its agent is given in every session, with an editor for each and buttons to add
and delete them. They live in the workspace, so it must be running.

Creating a workspace is a two-step wizard: the name, then the VM type, picked from cards (radios underneath) that show
each `/vm-sizes` entry's cores, memory and disk but not its price, with the default chosen. It always uses OpenRouter
and asks for no key for now. A failed create keeps the dialog and what was chosen, and goes to the page's error
banner. Every page has its own address, so a link, a reload or the browser's Back button lands where it should. `nginx.conf`
already answers every path with `index.html`, which is what makes a deep link load.

| Path                                   | Page                                                            |
|----------------------------------------|-----------------------------------------------------------------|
| `/`                                    | opens the first workspace                                       |
| `/workspaces/<id>`                     | its sessions, newest first, and a box that starts a new one     |
| `/workspaces/<id>/sessions/<session>`  | that session's chat                                             |
| `/workspaces/<id>/settings`            | its MCP servers and secrets                              |
| `/workspaces/<id>/terminal`            | an interactive Bash PTY through the workspace's OpenCode V2 service |
| `/store`                               | the recipe store: Public and Private tabs, search, categories, sort |
| `/store/<recipe>`                      | one recipe: its steps, author, dates, rating, Apply              |

The **Store** link in the top bar opens the recipe store. A recipe is Markdown the agent follows to set something up;
public ones are everyone's to apply and rate (one to five stars, never your own), private ones only their author's.
Apply picks a running workspace and starts a session there with the recipe as its first message. A recipe is written
in the editor, or with an agent: **Draft with the agent** starts a session that saves a draft in the workspace's
`~/.config/workspaced/recipes/`, and **Import a draft** opens one in the editor to review and save, removing the draft.
**Edit with the agent** copies the author's recipe into that folder with its id, so importing it updates the recipe.

A link to a session or workspace that no longer exists falls back to its workspace or home, replacing the history
entry so Back skips it. Typing a task on a workspace page creates a session and opens it; the session page sends the
text once its event stream is open, so the reply streams from its first token. History is fetched once per session,
then every change arrives over that session's server-sent event stream, and the history is fetched again whenever a
reply finishes, so a stream that opened mid-reply still ends up complete. The agent's text is rendered as markdown —
raw HTML in it is shown, never rendered — and its reasoning is folded away. The session is named by the workspace's own
model after the first exchange. While the agent works, Send becomes Stop.

Where a new piece goes: something with no parts of its own is an atom; a small group of atoms with one job is a
molecule; a self-contained region of the screen is an organism; layout without content is a template; and a page is
the only thing that knows which data fills it.

```sh
cd apps/web
npm run dev   # proxies /api to localhost:8080
npm test      # vitest, the architecture rules, and the 80% line-coverage floor
```

The model selector sits beside the composer on both new-task and session pages, and saves the workspace's model
choice for subsequent messages. User messages have an Edit action with Save & regenerate: this replaces the selected
turn and later history, retaining project files. A failed edit keeps its draft available even if the server already
shortened the conversation. While a reply is running, editing is disabled.

Where supported, microphone dictation appends recognized speech to the composer for review; stop dictation before
sending. It uses the browser's speech service and microphone permission, independently of the selected chat model.
The account menu links to Swagger at `/api/v1/swagger-ui` without polling the API health endpoint.

The terminal uses an ANSI color palette on a dark background. The harness image sets a color-capable terminal type
and installs an interactive Bash profile with a colored prompt and `ls` output. Existing pods need the rebuilt
harness image before the shell defaults change.


### Multiple public addresses

Settings → Web server → Public addresses adds independently enabled names, for example
`https://box-site.workspaced.online/` and `https://box-api.workspaced.online/`.
Disabling an address reserves its name; removing it releases the name. Other addresses
continue working. Existing single web-server names migrate automatically.

Every address terminates TLS at the public gateway and reaches the same workspace nginx
on port **80** through agent-hub and the VM supervisor. The original `Host`, request path,
query, visitor authorization, and WebSocket upgrades are retained. The harness password is
used only for harness traffic. An older supervisor rejects the new web exchange types.
The VM exposes nginx only on its loopback binding (`127.0.0.1:8080` → container `80`).

Users own `~/.config/workspaced/web/nginx.conf`. For example, add one server per app:

```nginx
server {
    listen 80;
    server_name box-site.workspaced.online;
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
    }
}
```

Keep these inside the existing `http` block and use `web-server reload` to validate/apply.
Another server block can route `box-api.workspaced.online` to another local port.
With agent access enabled, `web-server publish-domain <name>`, `disable-domain <name>`,
and `remove-domain <name>` manage additional addresses. Existing `publish` and `unpublish`
continue to manage the primary address.

The collection API is `GET /workspaces/{id}/web-server/domains`,
`PUT /workspaces/{id}/web-server/domains/{name}` with `{"enabled":true|false}`, and
`DELETE /workspaces/{id}/web-server/domains/{name}`. Workspace ownership is required;
invalid names return 400, global name conflicts return 409, and deleting another
workspace's address returns 404. Name uniqueness is enforced by PostgreSQL and deletion
of a workspace cascades to all its domain records.

Deploy the API, agent-hub, gateway, harness and supervisor together. Existing VM snapshots
pin the supervisor digest, so rebuild the `workspace-vm` snapshot after publishing the new
supervisor. Existing VMs also need that supervisor update; restarting only the harness does
not replace the VM's pinned supervisor. Existing persisted nginx configs are migrated from
`listen 8080;` to `listen 80;` on harness startup.

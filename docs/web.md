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

The app first asks the API who is signed in (`GET /api/v1/account`) and shows a sign-in page until someone is; signing in
takes an email and password. The session itself is an HttpOnly cookie the code never reads. Any `401` from the API
returns the app to the sign-in page, and Logout in the account menu clears the cookie. The profile on the settings page
(names and avatar) and the password change are saved to the account. Its Users tab lists everyone who can sign in,
sorted as chosen (newest first by default) and loaded 20 at a time as the end of the list scrolls into view; it adds a
user in a dialog and deletes anyone but yourself after a confirmation dialog. A workspace's settings have an Instructions section:
the workspace's instruction files, which its agent is given in every session, with an editor for each and buttons to add
and delete them. They live in the workspace, so it must be running.

Creating a workspace is a wizard: the name, then the VM type, picked from cards (radios underneath) that show each
`/vm-sizes` entry's cores, memory, disk and monthly price, cheapest first, with the default chosen while Hetzner has it.
The price sits apart on the right so it is never cut off. An unavailable low-cost size shows but cannot be picked, and
a pricier one shows only while it is available; while every low-cost one is unavailable a warning offers to check again,
and nothing pricier is chosen for the user. No text names the provider. A size that sells out during the create (`409`) brings the wizard back to the
sizes. Its last step takes an OpenRouter key, pasted or issued by **Connect OpenRouter**: OpenRouter's PKCE flow runs
in a popup, `/connect/openrouter` hands the code back over a `BroadcastChannel` (the sign-in can sever `window.opener`),
and the dialog exchanges it with OpenRouter directly for a key on the user's own account. Connecting drafts the
workspace first (`POST /workspaces` without a key) so the key is labelled `workspaced: <workspace id>`, then launches
that draft with it; going back, closing the dialog or a sold-out size discards the draft. A failed create keeps the dialog and what was chosen, and goes to the page's error
banner. Every page has its own address, so a link, a reload or the browser's Back button lands where it should. `nginx.conf`
already answers every path with `index.html`, which is what makes a deep link load.

| Path                                   | Page                                                            |
|----------------------------------------|-----------------------------------------------------------------|
| `/`                                    | opens the first workspace                                       |
| `/connect/openrouter`                  | OpenRouter's callback: relays the code and closes, no app       |
| `/workspaces/<id>`                     | its sessions, newest first, and a box that starts a new one     |
| `/workspaces/<id>/sessions/<session>`  | that session's chat                                             |
| `/workspaces/<id>/settings`            | its MCP servers and secrets                              |
| `/workspaces/<id>/terminal`            | an interactive Bash PTY through the workspace's OpenCode V2 service |
| `/store`                               | the recipe store: Public and Private tabs, search, categories, sort |
| `/store/<recipe>`                      | one recipe: its steps, author, dates, rating, Apply              |

The **Store** link in the top bar opens the recipe store. A recipe is Markdown the agent follows to set something up;
public ones are everyone's to apply and rate (one to five stars, never your own), private ones only their author's.
Apply picks a running workspace and starts a session there with the recipe as its first message. A recipe is written
in the editor, or imported: **Import a file** takes a Markdown file in the format of `docs/recipes/` (front matter, then
the steps) and publishes it at once, updating the recipe its `recipe:` id names when it has one.
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


## Guided WordPress trial

The tutorial opens automatically on a new account's first successful sign-in (`/?welcome`).
Returning sign-ins land at `/` and do not reopen it. An unfinished tutorial resumes on reload;
skipping or finishing clears its progress. Eligible accounts can also start it manually from
the home-page invitation or `/?tour`.
The tutorial explains a workspace, connects the owner's OpenRouter account with PKCE,
then presents the live VM catalogue. Only its cheapest available size is enabled for the
trial; other sizes explain whether they are sold out or excluded from the free trial.
The server chooses again at launch if capacity has changed. The machine runs for four
hours with no platform billing; OpenRouter bills the owner's AI usage separately.

The tour opens the public WordPress recipe, collects admin username/password as write-only
secrets, generates a separate database secret and restarts the workspace before applying
the recipe. It waits for an agent reply and asks the owner to check the public IP link.
It then schedules sourced technology news every five minutes, explains its OpenRouter
usage and deletion control, and returns to chat with an editable design suggestion.
The owner sends that last request themselves.

Progress and draft/session/workspace identifiers persist across reloads. Keys, passwords
and typed credentials never enter tour storage. Reloading before launch requires reconnecting
OpenRouter. A partial secrets save can be retried; saved values are not overwritten.

Migration V42 updates the starter WordPress instructions and Pavel Usanli byline without
changing V41's checksum. The WordPress recipe transfers from its seed account to
`pavel.usanli@gmail.com` on that account's next verified sign-in. Other starter recipes
remain owned by the platform seed account.

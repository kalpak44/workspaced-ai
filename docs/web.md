# Web app

React + Tailwind on Vite, in `apps/web`. Components follow atomic design; `test/architecture.test.ts` fails the build
when a rule below is broken.

```
src/
├── api/            fetch calls and their types — imported only by hooks/
├── hooks/          state and side effects (route, workspaces, sessions, chat, model providers, model settings, MCP servers,
│                   secrets, theme, API status, popover dismissal)
├── lib/            framework-free helpers (routes, relative times, token and price formatting, storage)
└── components/
    ├── atoms/      Logo, Button, IconButton, TextField, ChoiceCard, Spinner, Avatar, Divider — props in, markup out, no hooks
    ├── molecules/  ChatComposer, ChatMessageView, EmptyState, ErrorBanner, Markdown, Menu, Modal, PageHeader,
    │               SecretItem, SessionCard, SettingsSection, ThemeSwitch, WorkspaceItem, WorkspaceStatusIcon
    ├── organisms/  TopBar, UserMenu, Sidebar, CreateWorkspaceDialog, SessionList, ChatPanel, ModelPanel, McpPanel,
    │               SecretsPanel
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

Creating a workspace is a short wizard: the name, then a model provider picked from cards (radios underneath), whose
key field appears once one is chosen.
A key the provider refuses keeps the dialog open and marks the key field; anything else goes to the page's error
banner. Every page has its own address, so a link, a reload or the browser's Back button lands where it should. `nginx.conf`
already answers every path with `index.html`, which is what makes a deep link load.

| Path                                   | Page                                                            |
|----------------------------------------|-----------------------------------------------------------------|
| `/`                                    | opens the first workspace                                       |
| `/workspaces/<id>`                     | its sessions, newest first, and a box that starts a new one     |
| `/workspaces/<id>/sessions/<session>`  | that session's chat                                             |
| `/workspaces/<id>/settings`            | its model, MCP servers and secrets                              |
| `/workspaces/<id>/terminal`            | an interactive Bash PTY through the workspace's OpenCode V2 service |

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

# Web app

React + Tailwind on Vite, in `apps/web`. Components follow atomic design; `test/architecture.test.ts` fails the build
when a rule below is broken.

```
src/
├── api/            fetch calls and their types — imported only by hooks/
├── hooks/          state and side effects (workspaces, sessions, chat, model providers, secrets, theme, API status, popover dismissal)
├── lib/            framework-free helpers
└── components/
    ├── atoms/      Logo, Button, IconButton, TextField, ChoiceCard, Spinner, Avatar, Divider — props in, markup out, no hooks
    ├── molecules/  ChatComposer, ChatMessageView, EmptyState, ErrorBanner, Menu, Modal, NavItem, SecretItem, SessionItem,
    │               ThemeSwitch, WorkspaceItem, WorkspaceStatusIcon
    ├── organisms/  TopBar, UserMenu, Sidebar, CreateWorkspaceDialog, ChatPanel, SecretsPanel
    ├── templates/  AppLayout — the page frame, filled through props
    └── pages/      WorkspacesPage — wires hooks to organisms and picks what the main area shows
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
banner. A ready workspace opens on its chat: its sessions down the side, the open one's conversation beside them. The first
message in a workspace with no session open starts one. History is fetched once per session, then every change
arrives over that session's server-sent event stream, and the history is fetched again whenever a reply finishes, so
a stream that opened mid-reply still ends up complete. While the agent works, Send becomes Stop. The settings button
beside the title leads to the workspace's secrets, which can be added and removed but never read back.

Where a new piece goes: something with no parts of its own is an atom; a small group of atoms with one job is a
molecule; a self-contained region of the screen is an organism; layout without content is a template; and a page is
the only thing that knows which data fills it.

```sh
cd apps/web
npm run dev   # proxies /api to localhost:8080
npm test      # vitest, the architecture rules, and the 80% line-coverage floor
```

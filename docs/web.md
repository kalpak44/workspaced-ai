# Web app

React + Tailwind on Vite, in `apps/web`. Components follow atomic design; `test/architecture.test.ts` fails the build
when a rule below is broken.

```
src/
├── api/            fetch calls and their types — imported only by hooks/
├── hooks/          state and side effects (workspaces, theme, API status, popover dismissal)
├── lib/            framework-free helpers
└── components/
    ├── atoms/      Logo, Button, IconButton, TextField, Spinner, Avatar, Divider — props in, markup out, no hooks
    ├── molecules/  EmptyState, ErrorBanner, Menu, Modal, NavItem, ThemeSwitch, WorkspaceItem, WorkspaceStatusIcon
    ├── organisms/  TopBar, UserMenu, Sidebar, CreateWorkspaceDialog
    ├── templates/  AppLayout — the page frame, filled through props
    └── pages/      WorkspacesPage — wires hooks to organisms and picks what the main area shows
```

| Rule                                               | Why                                                        |
|----------------------------------------------------|------------------------------------------------------------|
| A layer imports only from the layers below it      | A change to an atom can never depend on a page             |
| Atoms use no hooks                                 | They stay reusable anywhere and trivial to test            |
| Only `hooks/` imports `api/`                       | Every request has one owner, and components stay mockable |
| Each layer exports through its `index.ts`          | Moving a file inside a layer changes no imports elsewhere  |

Where a new piece goes: something with no parts of its own is an atom; a small group of atoms with one job is a
molecule; a self-contained region of the screen is an organism; layout without content is a template; and a page is
the only thing that knows which data fills it.

```sh
cd apps/web
npm run dev   # proxies /api to localhost:8080
npm test      # vitest, the architecture rules, and the 80% line-coverage floor
```

/** Every page the app has; the URL is the only place the current one is kept. */
export type Route =
  | { readonly name: "home" }
  | { readonly name: "general-settings" }
  // `group` is the workspace a backup was made of, which may since have been deleted.
  | { readonly name: "backups"; readonly group?: string }
  | { readonly name: "workspace"; readonly workspaceId: string }
  | { readonly name: "settings"; readonly workspaceId: string }
  | { readonly name: "terminal"; readonly workspaceId: string; readonly terminalId?: string }
  | { readonly name: "session"; readonly workspaceId: string; readonly sessionId: string };

// Ids are UUIDs; matching only path-safe characters keeps a crafted URL from reaching an API
// route the page never meant to call.
const ID = "([A-Za-z0-9_-]+)";
const PATTERNS: ReadonlyArray<[RegExp, (ids: string[]) => Route]> = [
  [/^\/settings\/?$/, () => ({ name: "general-settings" })],
  [/^\/settings\/backups\/?$/, () => ({ name: "backups" })],
  [new RegExp(`^/settings/backups/${ID}/?$`), ([g]) => ({ name: "backups", group: g })],
  [new RegExp(`^/workspaces/${ID}/?$`), ([w]) => ({ name: "workspace", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/settings/?$`), ([w]) => ({ name: "settings", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/terminal/?$`), ([w]) => ({ name: "terminal", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/terminal/(pty[A-Za-z0-9_-]+)/?$`), ([w, t]) => ({ name: "terminal", workspaceId: w, terminalId: t })],
  [
    new RegExp(`^/workspaces/${ID}/sessions/${ID}/?$`),
    ([w, s]) => ({ name: "session", workspaceId: w, sessionId: s }),
  ],
];

/** Anything unrecognised is home, which then opens the first workspace. */
export function parseRoute(pathname: string): Route {
  for (const [pattern, build] of PATTERNS) {
    const match = pattern.exec(pathname);
    if (match) {
      return build(match.slice(1));
    }
  }
  return { name: "home" };
}

export function routePath(route: Route): string {
  switch (route.name) {
    case "home":
      return "/";
    case "general-settings":
      return "/settings";
    case "backups":
      return `/settings/backups${route.group ? `/${route.group}` : ""}`;
    case "workspace":
      return `/workspaces/${route.workspaceId}`;
    case "settings":
      return `/workspaces/${route.workspaceId}/settings`;
    case "terminal":
      return `/workspaces/${route.workspaceId}/terminal${route.terminalId ? `/${route.terminalId}` : ""}`;
    case "session":
      return `/workspaces/${route.workspaceId}/sessions/${route.sessionId}`;
  }
}

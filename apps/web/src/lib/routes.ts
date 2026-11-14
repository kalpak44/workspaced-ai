/** Every page the app has; the URL is the only place the current one is kept. */
export type Route =
  | { readonly name: "home" }
  | { readonly name: "general-settings" }
  | { readonly name: "store" }
  | { readonly name: "recipe"; readonly recipeId: string }
  | { readonly name: "workspace"; readonly workspaceId: string }
  | { readonly name: "settings"; readonly workspaceId: string; readonly section?: string }
  | { readonly name: "terminal"; readonly workspaceId: string; readonly terminalId?: string }
  | { readonly name: "session"; readonly workspaceId: string; readonly sessionId: string };

// Ids are UUIDs; matching only path-safe characters keeps a crafted URL from reaching an API
// route the page never meant to call.
const ID = "([A-Za-z0-9_-]+)";
const PATTERNS: ReadonlyArray<[RegExp, (ids: string[]) => Route]> = [
  [/^\/settings\/?$/, () => ({ name: "general-settings" })],
  [/^\/store\/?$/, () => ({ name: "store" })],
  [new RegExp(`^/store/${ID}/?$`), ([r]) => ({ name: "recipe", recipeId: r })],
  [new RegExp(`^/workspaces/${ID}/?$`), ([w]) => ({ name: "workspace", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/settings/?$`), ([w]) => ({ name: "settings", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/settings/([a-z-]+)/?$`), ([w, s]) => ({ name: "settings", workspaceId: w, section: s })],
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
    case "store":
      return "/store";
    case "recipe":
      return `/store/${route.recipeId}`;
    case "workspace":
      return `/workspaces/${route.workspaceId}`;
    case "settings":
      return `/workspaces/${route.workspaceId}/settings${route.section ? `/${route.section}` : ""}`;
    case "terminal":
      return `/workspaces/${route.workspaceId}/terminal${route.terminalId ? `/${route.terminalId}` : ""}`;
    case "session":
      return `/workspaces/${route.workspaceId}/sessions/${route.sessionId}`;
  }
}

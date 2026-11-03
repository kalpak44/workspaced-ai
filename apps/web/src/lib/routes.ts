/** Every page the app has; the URL is the only place the current one is kept. */
export type Route =
  | { readonly name: "home" }
  | { readonly name: "workspace"; readonly workspaceId: string }
  | { readonly name: "settings"; readonly workspaceId: string }
  | { readonly name: "session"; readonly workspaceId: string; readonly sessionId: string };

// Ids are UUIDs; matching only path-safe characters keeps a crafted URL from reaching an API
// route the page never meant to call.
const ID = "([A-Za-z0-9_-]+)";
const PATTERNS: ReadonlyArray<[RegExp, (ids: string[]) => Route]> = [
  [new RegExp(`^/workspaces/${ID}/?$`), ([w]) => ({ name: "workspace", workspaceId: w })],
  [new RegExp(`^/workspaces/${ID}/settings/?$`), ([w]) => ({ name: "settings", workspaceId: w })],
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
    case "workspace":
      return `/workspaces/${route.workspaceId}`;
    case "settings":
      return `/workspaces/${route.workspaceId}/settings`;
    case "session":
      return `/workspaces/${route.workspaceId}/sessions/${route.sessionId}`;
  }
}

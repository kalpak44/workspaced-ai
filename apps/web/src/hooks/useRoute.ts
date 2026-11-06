import { useMemo, useSyncExternalStore } from "react";
import { parseRoute, type Route, routePath } from "../lib/routes";

export type { Route } from "../lib/routes";

// pushState fires no event of its own, so navigate() announces the change with this one.
const NAVIGATED = "workspaced:navigated";
// Marks history entries this app pushed, so goBack() knows Back stays inside the app.
const IN_APP = { inApp: true };

function subscribe(onChange: () => void): () => void {
  globalThis.addEventListener("popstate", onChange);
  globalThis.addEventListener(NAVIGATED, onChange);
  return () => {
    globalThis.removeEventListener("popstate", onChange);
    globalThis.removeEventListener(NAVIGATED, onChange);
  };
}

/**
 * Goes to a page. `replace` swaps the current history entry, for a redirect the user did not
 * ask for, so Back does not land on the page that redirected.
 */
export function navigate(route: Route, { replace = false } = {}): void {
  const path = routePath(route);
  if (path === globalThis.location.pathname) {
    return;
  }
  if (replace) {
    globalThis.history.replaceState(globalThis.history.state, "", path);
  } else {
    globalThis.history.pushState(IN_APP, "", path);
  }
  globalThis.dispatchEvent(new Event(NAVIGATED));
}

/**
 * Returns to the previous page, like the browser's Back. A page opened directly by its link has
 * no previous page here, so it goes to `fallback` instead of leaving the app.
 */
export function goBack(fallback: Route): void {
  if ((globalThis.history.state as typeof IN_APP | null)?.inApp) {
    globalThis.history.back();
  } else {
    navigate(fallback, { replace: true });
  }
}

/** The page the URL names; Back and Forward change it like any other navigation. */
export function useRoute(): Route {
  const path = useSyncExternalStore(subscribe, () => globalThis.location.pathname);
  return useMemo(() => parseRoute(path), [path]);
}

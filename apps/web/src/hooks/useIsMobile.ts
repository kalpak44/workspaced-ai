import { useSyncExternalStore } from "react";

const QUERY = "(max-width: 639px)";

function subscribe(onChange: () => void) {
  const media = globalThis.matchMedia?.(QUERY);
  media?.addEventListener("change", onChange);
  return () => media?.removeEventListener("change", onChange);
}

/** Matches the small-screen layout breakpoint, including after rotation or resizing. */
export function useIsMobile() {
  return useSyncExternalStore(subscribe, () => globalThis.matchMedia?.(QUERY).matches ?? false, () => false);
}

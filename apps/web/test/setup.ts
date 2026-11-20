import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";
import { resetCsrfToken } from "../src/api/http";
import { resetAccount } from "../src/hooks/useAccount";
import { resetNotifications } from "../src/hooks/useNotifications";
import { setTourProgress } from "../src/lib/tour";

afterEach(() => {
  cleanup();
  resetAccount();
  resetNotifications();
  setTourProgress(null);
  resetCsrfToken();
  localStorage.clear();
  document.documentElement.className = "";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  // The URL is the app's state; each test starts from the root.
  globalThis.history.replaceState(null, "", "/");
});

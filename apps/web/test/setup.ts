import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";
import { resetCsrfToken } from "../src/api/http";
import { resetAccount } from "../src/hooks/useAccount";

afterEach(() => {
  cleanup();
  resetAccount();
  resetCsrfToken();
  localStorage.clear();
  document.documentElement.className = "";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  // The URL is the app's state; each test starts from the root.
  globalThis.history.replaceState(null, "", "/");
});

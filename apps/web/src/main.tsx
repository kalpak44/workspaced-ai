import { StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { App } from "./App";
import { AppRecoveryBoundary } from "./components/templates/AppRecoveryBoundary";
import { relayOpenRouterCallback } from "./lib/openRouterCallback";
import "./style.css";

const container = document.getElementById("root");

/**
 * The mounted app, or null on a page without a root or on OpenRouter's callback, which only hands
 * its code back to the window that asked; tests unmount it to release its effects.
 */
export const appRoot: Root | null = container && !relayOpenRouterCallback() ? createRoot(container) : null;

if (appRoot) {
  appRoot.render(
    <StrictMode>
      <AppRecoveryBoundary><App /></AppRecoveryBoundary>
    </StrictMode>,
  );
}

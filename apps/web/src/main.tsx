import { StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { App } from "./App";
import "./style.css";

const container = document.getElementById("root");

/** The mounted app, or null on a page without a root; tests unmount it to release its effects. */
export const appRoot: Root | null = container ? createRoot(container) : null;

if (appRoot) {
  appRoot.render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

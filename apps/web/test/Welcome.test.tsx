import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

/** The app as the API's sign-in callback leaves it, at `path`, signed in. */
async function signedInAt(path: string) {
  installFakeApi();
  globalThis.history.replaceState(null, "", path);
  render(<App />);
  await screen.findAllByRole("button", { name: "Starter" });
}

describe("welcome", () => {
  it("welcomes a new account once, and drops the parameter so a reload does not repeat it", async () => {
    await signedInAt("/?welcome");
    const dialog = screen.getByRole("dialog", { name: "Welcome to workspaced" });
    expect(dialog).toHaveTextContent("A workspace is a sandbox with its own AI agent");
    expect(globalThis.location.pathname + globalThis.location.search).toBe("/");

    await userEvent.setup().click(within(dialog).getByRole("button", { name: "Later" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("goes to billing to add funds", async () => {
    await signedInAt("/?welcome");
    await userEvent.setup().click(screen.getByRole("button", { name: "Add funds" }));
    await waitFor(() => expect(globalThis.location.pathname).toBe("/billing"));
    expect(screen.queryByRole("dialog", { name: "Welcome to workspaced" })).not.toBeInTheDocument();
    expect(await screen.findByText("Current balance")).toBeInTheDocument();
  });

  it("welcomes no one who lands on / without the parameter", async () => {
    await signedInAt("/");
    expect(screen.queryByRole("dialog", { name: "Welcome to workspaced" })).not.toBeInTheDocument();
  });
});

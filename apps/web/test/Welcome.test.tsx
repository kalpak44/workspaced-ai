import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

/** The app as the API's sign-in callback leaves it, at `path`, signed in. */
async function signedInAt(path: string, { demoAvailable = true, workspaces = true } = {}) {
  const api = installFakeApi(workspaces ? undefined : []);
  api.auth.account = { ...api.auth.account, role: "USER", demoAvailable };
  globalThis.history.replaceState(null, "", path);
  render(<App />);
  if (workspaces) await screen.findAllByRole("button", { name: "Starter" });
  return api;
}

describe("welcome", () => {
  it("opens the tutorial automatically on the first-login callback and drops the parameter", async () => {
    await signedInAt("/?welcome");
    const dialog = await screen.findByRole("region", { name: "Guided tour" });
    expect(dialog).toHaveTextContent("Guided tour");
    expect(dialog).toHaveTextContent("Just click Next");
    expect(dialog).toHaveTextContent("a trial token will provide $1 of AI usage");
    expect(globalThis.location.pathname + globalThis.location.search).toBe("/");
    await userEvent.setup().click(screen.getByRole("button", { name: "Skip tour" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("does not automatically start another tutorial after the trial was used", async () => {
    await signedInAt("/?welcome", { demoAvailable: false });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("starts the tour straight from the welcome mail's link", async () => {
    await signedInAt("/?tour");
    expect(await screen.findByText(/Just click/)).toBeInTheDocument();
    expect(globalThis.location.search).toBe("");
  });

  it("does not restart a tour skipped from the welcome mail link", async () => {
    await signedInAt("/?tour");
    await userEvent.setup().click(await screen.findByRole("button", { name: "Skip tour" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("invites an account with no workspaces to the tour from the empty page", async () => {
    await signedInAt("/", { workspaces: false });
    const invitation = await screen.findByRole("region", { name: "Guided tour" });
    await userEvent.setup().click(within(invitation).getByRole("button", { name: "Start the tour" }));
    expect(await screen.findByText(/Just click/)).toBeInTheDocument();
  });

  it("welcomes no one who lands on / without the parameter", async () => {
    await signedInAt("/");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});

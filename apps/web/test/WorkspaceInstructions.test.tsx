import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

vi.mock("@xterm/xterm", () => ({ Terminal: class {} }));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class {} }));

let api: ReturnType<typeof installFakeApi>;

beforeEach(() => {
  api = installFakeApi();
});

type User = ReturnType<typeof userEvent.setup>;

async function openInstructions(user: User) {
  globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "AI instructions" }));
  return screen.findByRole("region", { name: "Workspace instructions" });
}

describe("workspace AI instructions", () => {
  it("shows the read-only guide and saves the workspace's own instructions to it", async () => {
    const user = userEvent.setup();
    await openInstructions(user);
    await user.click(screen.getByText("Show the guide"));
    expect(screen.getByText("Only the home directory survives a restart.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: { value: "Use pnpm." } });
    await user.click(screen.getByRole("button", { name: "Save instructions" }));

    expect(await screen.findByText(/uses them from its next message/)).toBeInTheDocument();
    expect(api.instructions.get("starter")).toBe("Use pnpm.");
    expect(screen.getByRole("button", { name: "Save instructions" })).toBeDisabled();
  });

  it("says a stopped workspace gets them when it next starts", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    await openInstructions(user);
    expect(screen.getByText("Start the workspace to see its guide.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: { value: "Use pnpm." } });
    await user.click(screen.getByRole("button", { name: "Save instructions" }));
    expect(await screen.findByText(/gets them when it next starts/)).toBeInTheDocument();
  });

  it("shows an example while empty, which is never saved", async () => {
    const user = userEvent.setup();
    await openInstructions(user);
    const editor = screen.getByRole("textbox", { name: "AI instructions editor" });
    await waitFor(() => expect(editor.querySelector("[data-placeholder]")?.getAttribute("data-placeholder")).toContain("Ask before deleting or replacing anything."));
    expect(screen.getByRole("button", { name: "Save instructions" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const raw = screen.getByRole("textbox", { name: "AI instructions Markdown" });
    expect(raw).toHaveValue("");
    expect(raw.getAttribute("placeholder")).toContain("Ask before deleting or replacing anything.");
    expect(api.instructions.has("starter")).toBe(false);
  });

  it("links to the general instructions it builds on", async () => {
    const user = userEvent.setup();
    await openInstructions(user);
    await user.click(screen.getByRole("button", { name: "general AI instructions" }));
    expect(globalThis.location.pathname).toBe("/settings");
  });
});

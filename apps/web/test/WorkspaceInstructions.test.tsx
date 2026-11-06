import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

/** Workspace settings, Instructions section, once its files have loaded. */
async function openInstructions(user: User) {
  globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Instructions" }));
  return screen.findByRole("navigation", { name: "Instruction files" });
}

async function typeRaw(user: User, value: string) {
  await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
  fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: { value } });
}

describe("workspace instructions", () => {
  it("opens with the workspace's files and saves an edited one", async () => {
    const user = userEvent.setup();
    const files = await openInstructions(user);
    await waitFor(() => expect(within(files).getByRole("button", { name: /working-style/ })).toHaveAttribute("aria-current", "page"));

    await typeRaw(user, "- Keep answers very short.");
    expect(within(files).getByLabelText("Unsaved changes")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/uses them from its next message/)).toBeInTheDocument();
    expect(api.instructions.get("starter")?.get("working-style")).toBe("- Keep answers very short.");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("adds a new file, refusing a name that is not a plain file name", async () => {
    const user = userEvent.setup();
    await openInstructions(user);
    await user.click(screen.getByRole("button", { name: "New instructions" }));
    const form = screen.getByRole("form", { name: "New instructions" });
    await user.type(within(form).getByLabelText("Name"), "../secrets");
    expect(screen.getByText(/Use up to 63 letters/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();

    await user.clear(within(form).getByLabelText("Name"));
    await user.type(within(form).getByLabelText("Name"), "code-review");
    await user.click(screen.getByRole("button", { name: "Create" }));
    const files = screen.getByRole("navigation", { name: "Instruction files" });
    expect(await within(files).findByRole("button", { name: /code-review/ })).toHaveAttribute("aria-current", "page");

    await typeRaw(user, "- Point out missing tests.");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText(/uses them from its next message/);
    expect(api.instructions.get("starter")?.get("code-review")).toBe("- Point out missing tests.");
  });

  it("keeps unsaved edits per file while switching between them", async () => {
    api.instructions.set("starter", new Map([["a-file", "A"], ["b-file", "B"]]));
    const user = userEvent.setup();
    const files = await openInstructions(user);
    await waitFor(() => expect(within(files).getByRole("button", { name: /a-file/ })).toHaveAttribute("aria-current", "page"));
    await typeRaw(user, "A changed");
    await user.click(within(files).getByRole("button", { name: /b-file/ }));
    expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue("B");
    await user.click(within(files).getByRole("button", { name: /a-file/ }));
    expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue("A changed");
  });

  it("deletes a file only once confirmed", async () => {
    const user = userEvent.setup();
    await openInstructions(user);
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Keep" }));
    expect(api.instructions.get("starter")?.has("working-style")).toBe(true);

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Delete working-style" }));
    expect(await screen.findByText("No instructions yet. Add some with New instructions.")).toBeInTheDocument();
    expect(api.instructions.get("starter")?.size).toBe(0);
  });

  it("asks to start a workspace that is not running", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Instructions" }));
    expect(await screen.findByText(/Start it to see and edit them/)).toBeInTheDocument();
  });
});

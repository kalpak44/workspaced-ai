import { render, screen, within } from "@testing-library/react";
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

async function openVariables(user: User) {
  globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Variables" }));
}

describe("workspace variables", () => {
  it("edits, adds and removes variables, then saves them all and offers a restart", async () => {
    api.variables.set("starter", [{ name: "LOG_LEVEL", value: "info" }, { name: "OLD", value: "x" }]);
    const user = userEvent.setup();
    await openVariables(user);
    const value = await screen.findByLabelText("Value 1");
    expect(value).toHaveValue("info");
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.clear(value);
    await user.type(value, "debug");
    await user.click(screen.getByRole("button", { name: "Remove OLD" }));
    await user.click(screen.getByRole("button", { name: "Add variable" }));
    await user.type(screen.getByLabelText("Name 2"), "PORT");
    await user.type(screen.getByLabelText("Value 2"), "8080");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/from its next restart/)).toBeInTheDocument();
    expect(api.variables.get("starter")).toEqual([{ name: "LOG_LEVEL", value: "debug" }, { name: "PORT", value: "8080" }]);
    expect(screen.getByRole("button", { name: "Restart workspace" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("refuses names the workspace could not set", async () => {
    const user = userEvent.setup();
    await openVariables(user);
    await user.click(await screen.findByRole("button", { name: "Add variable" }));
    const list = screen.getByRole("list", { name: "Variables" });
    await user.type(within(list).getByLabelText("Name 1"), "1BAD");
    expect(screen.getByText(/not starting with a digit/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    await user.clear(within(list).getByLabelText("Name 1"));
    await user.type(within(list).getByLabelText("Name 1"), "OPENCODE_SERVER_USERNAME");
    expect(screen.getByText("Set by the workspace itself.")).toBeInTheDocument();

    await user.clear(within(list).getByLabelText("Name 1"));
    await user.type(within(list).getByLabelText("Name 1"), "A");
    await user.click(screen.getByRole("button", { name: "Add variable" }));
    await user.type(within(list).getByLabelText("Name 2"), "A");
    expect(screen.getAllByText("Given twice.")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("asks to start a workspace that is not running", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    await openVariables(user);
    expect(await screen.findByText(/Start it to see and edit them/)).toBeInTheDocument();
  });
});

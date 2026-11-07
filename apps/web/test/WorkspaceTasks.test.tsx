import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { cronError, validTimeZone } from "../src/lib/cron";
import { installFakeApi } from "./fakeApi";

vi.mock("@xterm/xterm", () => ({ Terminal: class {} }));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class {} }));

let api: ReturnType<typeof installFakeApi>;

beforeEach(() => {
  api = installFakeApi();
});

type User = ReturnType<typeof userEvent.setup>;

const PR_SUMMARY = { cron: "0 9 * * 1-5", timeZone: "Europe/Sofia", prompt: "Summarise the open PRs." };

/** Workspace settings, Tasks section, once its tasks have loaded. */
async function openTasks(user: User) {
  globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Tasks" }));
  return screen.findByRole("navigation", { name: "Tasks" });
}

async function typePrompt(user: User, value: string) {
  await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Task prompt Markdown" }), { target: { value } });
}

describe("workspace tasks", () => {
  it("opens with the workspace's tasks and saves an edited schedule and prompt", async () => {
    api.tasks.set("starter", new Map([["pr-summary", PR_SUMMARY]]));
    const user = userEvent.setup();
    const list = await openTasks(user);
    await waitFor(() => expect(within(list).getByRole("button", { name: /pr-summary/ })).toHaveAttribute("aria-current", "page"));
    expect(screen.getByLabelText("Cron expression")).toHaveValue("0 9 * * 1-5");

    fireEvent.change(screen.getByLabelText("Cron expression"), { target: { value: "30 17 * * FRI" } });
    await typePrompt(user, "Write the weekly summary.");
    expect(within(list).getByLabelText("Unsaved changes")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/runs at the next matching time/)).toBeInTheDocument();
    expect(api.tasks.get("starter")?.get("pr-summary")).toEqual({ ...PR_SUMMARY, cron: "30 17 * * FRI", prompt: "Write the weekly summary." });
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("refuses to save an invalid cron expression or time zone", async () => {
    api.tasks.set("starter", new Map([["pr-summary", PR_SUMMARY]]));
    const user = userEvent.setup();
    await openTasks(user);
    fireEvent.change(await screen.findByLabelText("Cron expression"), { target: { value: "0 25 * * *" } });
    expect(screen.getByText("The hour field is not valid.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Cron expression"), { target: { value: "0 9 * * *" } });
    fireEvent.change(screen.getByLabelText("Time zone"), { target: { value: "Mars/Olympus" } });
    expect(screen.getByText(/Use a time zone such as/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("adds a task with a default schedule, refusing a name that is not a plain file name", async () => {
    const user = userEvent.setup();
    await openTasks(user);
    expect(screen.getByText("No tasks yet. Add one with New task.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "New task" }));
    const form = screen.getByRole("form", { name: "New task" });
    await user.type(within(form).getByLabelText("Name"), "../secrets");
    expect(screen.getByRole("button", { name: "Create" })).toBeDisabled();

    await user.clear(within(form).getByLabelText("Name"));
    await user.type(within(form).getByLabelText("Name"), "pr-summary");
    await user.click(screen.getByRole("button", { name: "Create" }));
    const list = screen.getByRole("navigation", { name: "Tasks" });
    expect(await within(list).findByRole("button", { name: /pr-summary/ })).toHaveAttribute("aria-current", "page");
    expect(api.tasks.get("starter")?.get("pr-summary")?.cron).toBe("0 9 * * 1-5");
  });

  it("deletes a task only once confirmed", async () => {
    api.tasks.set("starter", new Map([["pr-summary", PR_SUMMARY]]));
    const user = userEvent.setup();
    await openTasks(user);
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Keep" }));
    expect(api.tasks.get("starter")?.has("pr-summary")).toBe(true);

    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Delete pr-summary" }));
    expect(await screen.findByText("No tasks yet. Add one with New task.")).toBeInTheDocument();
    expect(api.tasks.get("starter")?.size).toBe(0);
  });

  it("asks to start a workspace that is not running", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    globalThis.history.replaceState(null, "", "/workspaces/starter/settings");
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Tasks" }));
    expect(await screen.findByText(/Start it to see and edit them/)).toBeInTheDocument();
  });
});

describe("cron expressions", () => {
  it.each(["0 9 * * 1-5", "*/15 * * * *", "0 0 1,15 * *", "30 17 * jan-mar fri", "0 0 * * 7", "@daily"])("accepts %s", (expression) => {
    expect(cronError(expression)).toBeNull();
  });

  it.each([
    ["", "Enter when the task runs."],
    ["0 9 * *", "Use five fields: minute, hour, day of month, month, day of week."],
    ["60 * * * *", "The minute field is not valid."],
    ["0 9 0 * *", "The day of month field is not valid."],
    ["0 9 * 13 *", "The month field is not valid."],
    ["0 9 * * 5-1", "The day of week field is not valid."],
    ["*/0 * * * *", "The minute field is not valid."],
  ])("refuses %j", (expression, message) => {
    expect(cronError(expression)).toBe(message);
  });

  it("knows real time zones", () => {
    expect(validTimeZone("Europe/Sofia")).toBe(true);
    expect(validTimeZone("Mars/Olympus")).toBe(false);
  });
});

import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { App } from "../src/App";
import { FakeEventSource, installFakeApi } from "./fakeApi";

let api: ReturnType<typeof installFakeApi>;
beforeEach(() => {
  api = installFakeApi();
  api.sessions.set("starter", [
    { id: "first", title: "Current conversation", path: "/p/first", createdAt: new Date().toISOString() },
    { id: "second", title: "Other conversation", path: "/p/second", createdAt: new Date().toISOString() },
  ]);
  history.replaceState(null, "", "/workspaces/starter/sessions/first");
});

describe("session agent picker", () => {
  it("switches the agent while preserving the draft, model, and conversation", async () => {
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("button", { name: "Agent: Build" });
    await user.type(screen.getByRole("textbox", { name: "Message" }), "Keep this draft");
    const model = screen.getByRole("button", { name: /Model:/ }).getAttribute("aria-label");
    await user.click(screen.getByRole("button", { name: "Agent: Build" }));
    expect(await screen.findByRole("radio", { name: /Build/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Writer/ })).toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: /Explore/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: /Plan/ }));
    await waitFor(() => expect(screen.getByRole("radio", { name: /Plan/ })).toBeChecked());
    await user.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.getByRole("button", { name: "Agent: Plan" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue("Keep this draft");
    expect(screen.getByRole("button", { name: /Model:/ })).toHaveAttribute("aria-label", model);
    expect(api.prompts).toEqual([]);
    expect(api.sessions.get("starter")).toHaveLength(2);
    expect(api.sessionAgents.get("first")).toBe("plan");
    expect(api.fetchFn.mock.calls.filter(([, init]) => init?.method === "PUT").map(([url, init]) => [url, JSON.parse(String(init!.body))])).toEqual([
      ["/api/v1/workspaces/starter/sessions/first/agent", { agent: "plan" }],
    ]);
  });

  it("blocks sending and further agent/model changes until the switch is acknowledged", async () => {
    let release!: () => void;
    api.state.holdAgentSwitch = new Promise<void>((resolve) => { release = resolve; });
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Agent: Build" }));
    await user.click(await screen.findByRole("radio", { name: /Plan/ }));
    expect(screen.getByRole("textbox", { name: "Message" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Model:/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /Writer/ })).toBeDisabled();
    await act(async () => release());
    await waitFor(() => expect(screen.getByRole("radio", { name: /Plan/ })).toBeChecked());
    expect(screen.getByRole("textbox", { name: "Message" })).toBeEnabled();
  });

  it("reports discovery and switch failures and reloads the authoritative selection", async () => {
    api.state.failAgentList = true;
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: "Agent: Choose agent" }));
    await screen.findByText(/Could not load the agents/);
    api.state.failAgentList = false;
    await user.click(screen.getByRole("button", { name: "Reload agents" }));
    await screen.findByRole("radio", { name: /Plan/ });
    api.state.failAgentSwitch = true;
    await user.click(screen.getByRole("radio", { name: /Plan/ }));
    await screen.findByText(/Could not confirm the agent change/);
    expect(screen.getByRole("radio", { name: /Build/ })).toBeChecked();
    api.sessionAgents.set("first", "writer");
    await user.click(screen.getByRole("button", { name: "Reload agents" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: /Writer/ })).toBeChecked());
  });

  it("keeps choices scoped to each session and reads them back on reload", async () => {
    api.sessionAgents.set("first", "plan");
    api.sessionAgents.set("second", "writer");
    const user = userEvent.setup();
    const view = render(<App />);
    await screen.findByRole("button", { name: "Agent: Plan" });
    await user.click(screen.getByRole("button", { name: "Other conversation" }));
    await screen.findByRole("button", { name: "Agent: Writer" });
    view.unmount();
    render(<App />);
    await screen.findByRole("button", { name: "Agent: Writer" });
    expect(api.sessionAgents.get("first")).toBe("plan");
  });

  it("disables switching while the assistant is working", async () => {
    render(<App />);
    const picker = await screen.findByRole("button", { name: "Agent: Build" });
    FakeEventSource.emitSession("status", { busy: true });
    expect(picker).toBeDisabled();
    FakeEventSource.emitSession("status", { busy: false });
    expect(picker).toBeEnabled();
  });
});

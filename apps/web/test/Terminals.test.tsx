import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import type { TerminalSession } from "../src/api/terminalsApi";
import { installFakeApi } from "./fakeApi";

const consoles = vi.hoisted(() => [] as { write: ReturnType<typeof vi.fn>; input?: (data: string) => void; disposed: boolean }[]);
vi.mock("@xterm/xterm", () => ({ Terminal: class {
  cols = 100; rows = 30;
  own = { write: vi.fn(), input: undefined as ((data: string) => void) | undefined, disposed: false };
  constructor() { consoles.push(this.own); }
  loadAddon() {} open() {} focus() {} writeln() {}
  write(data: unknown) { this.own.write(data); }
  onData(callback: (data: string) => void) { this.own.input = callback; return { dispose() {} }; }
  dispose() { this.own.disposed = true; }
} }));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class { fit() {} } }));

class FakeSocket extends EventTarget {
  static readonly OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = 1;
  binaryType = "";
  closed = false;
  send = vi.fn();
  readonly url: string;
  constructor(url: string) { super(); this.url = url; FakeSocket.instances.push(this); queueMicrotask(() => this.dispatchEvent(new Event("open"))); }
  close() { this.closed = true; this.readyState = 3; this.dispatchEvent(new CloseEvent("close", { code: 1000 })); }
}

let api: ReturnType<typeof installFakeApi>;
beforeEach(() => {
  consoles.length = 0;
  FakeSocket.instances = [];
  vi.stubGlobal("WebSocket", FakeSocket);
  vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} });
  api = installFakeApi();
  history.replaceState(null, "", "/workspaces/starter/terminal");
});

const terminal = (number: number, status: "running" | "exited" = "running"): TerminalSession => ({ id: `pty_${number}`, title: `Terminal ${number}`, command: "/bin/bash", cwd: "/home/workspace/project", status, pid: 100 + number, exitCode: status === "exited" ? 7 : null });
const lastSocket = () => FakeSocket.instances.at(-1)!;

describe("workspace terminals", () => {
  it("creates and switches shells, detaching without deleting, and reconnects after remount", async () => {
    const user = userEvent.setup();
    const view = render(<App />);
    await user.click(await screen.findByRole("button", { name: "Create terminal" }));
    await screen.findByRole("heading", { name: "Terminal 1" });
    await waitFor(() => expect(lastSocket()?.url).toContain("/terminals/pty_1/connect"));
    const firstSocket = lastSocket();
    await user.click(screen.getByRole("button", { name: "New terminal" }));
    await screen.findByRole("heading", { name: "Terminal 2" });
    expect(firstSocket.closed).toBe(true);
    expect(api.terminals.get("starter")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Terminal 1 Running" }));
    await waitFor(() => expect(lastSocket().url).toContain("/terminals/pty_1/connect"));
    expect(screen.getByRole("button", { name: "Terminal 1 Running" })).toHaveAttribute("aria-current", "page");
    expect(api.fetchFn.mock.calls.filter(([, init]) => init?.method === "DELETE")).toHaveLength(0);
    view.unmount();
    render(<App />);
    await screen.findByRole("heading", { name: "Terminal 1" });
    expect(api.terminals.get("starter")).toHaveLength(2);
    expect(api.fetchFn.mock.calls.filter(([url, init]) => String(url).endsWith("/terminals") && init?.method === "POST")).toHaveLength(2);
  });

  it("relays binary input/output and resize, and reconnects to the same shell after transport failure", async () => {
    api.terminals.set("starter", [terminal(1)]);
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("heading", { name: "Terminal 1" });
    await waitFor(() => expect(lastSocket()?.send).toHaveBeenCalledWith("resize:100:30"));
    const socket = lastSocket();
    act(() => consoles.at(-1)!.input!("pwd\n"));
    expect(new TextDecoder().decode(socket.send.mock.calls.at(-1)![0])).toBe("pwd\n");
    const encoded = new TextEncoder().encode("/home/workspace/project");
    const bytes = new ArrayBuffer(encoded.length);
    new Uint8Array(bytes).set(encoded);
    act(() => socket.dispatchEvent(new MessageEvent("message", { data: bytes })));
    expect(consoles.at(-1)!.write).toHaveBeenCalledWith(new Uint8Array(bytes));
    act(() => socket.dispatchEvent(new CloseEvent("close", { code: 1011 })));
    await user.click(await screen.findByRole("button", { name: "Reconnect terminal" }));
    await waitFor(() => expect(lastSocket()).not.toBe(socket));
    expect(lastSocket().url).toBe(socket.url);
    expect(api.terminals.get("starter")).toHaveLength(1);
  });

  it("confirms deletion, retains failed deletes, and deletes only the selected shell", async () => {
    api.terminals.set("starter", [terminal(1), terminal(2), terminal(3)]);
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("heading", { name: "Terminal 1" });
    await user.click(screen.getByRole("button", { name: "Delete terminal: Terminal 1" }));
    const dialog = screen.getByRole("dialog", { name: "Delete terminal?" });
    expect(dialog).toHaveTextContent("Terminal 1");
    expect(api.terminals.get("starter")).toHaveLength(3);
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Delete terminal: Terminal 1" }));
    api.state.failTerminalDelete = true;
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete terminal" }));
    await waitFor(() => expect(screen.getByRole("dialog")).toHaveTextContent("Could not delete the terminal"));
    expect(api.terminals.get("starter")).toHaveLength(3);
    api.state.failTerminalDelete = false;
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete terminal" }));
    await screen.findByRole("heading", { name: "Terminal 2" });
    expect(api.terminals.get("starter")?.map((item) => item.id)).toEqual(["pty_2", "pty_3"]);
    await user.click(screen.getByRole("button", { name: "Delete terminal: Terminal 3" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Terminal 3");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete terminal" }));
    await waitFor(() => expect(api.terminals.get("starter")?.map((item) => item.id)).toEqual(["pty_2"]));
    expect(screen.getByRole("heading", { name: "Terminal 2" })).toBeInTheDocument();
    expect(api.rows).toHaveLength(1);
  });

  it("filters many terminals and displays exited sessions without trying to restart them", async () => {
    api.terminals.set("starter", Array.from({ length: 35 }, (_, index) => terminal(index + 1, index === 24 ? "exited" : "running")));
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("heading", { name: "Terminal 1" });
    await user.type(screen.getByRole("searchbox", { name: "Search terminals" }), "Terminal 25");
    const nav = screen.getByRole("navigation", { name: "Terminal sessions" });
    expect(within(nav).getAllByRole("listitem")).toHaveLength(1);
    await user.click(within(nav).getByRole("button", { name: "Terminal 25 Exited · 7" }));
    await screen.findByRole("heading", { name: "Terminal 25" });
    expect(screen.getByText("Exit code 7")).toBeInTheDocument();
    expect(lastSocket().url).not.toContain("pty_25");
    expect(screen.getByText(/This shell has exited/)).toBeInTheDocument();
  });

  it("reports creation and loading failures and retries without inventing local terminals", async () => {
    api.state.harnessDown = true;
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText(/Could not load terminals/);
    api.state.harnessDown = false;
    await user.click(screen.getByRole("button", { name: "Refresh terminals" }));
    await screen.findByRole("heading", { name: "No terminals yet" });
    api.state.failTerminalCreate = true;
    await user.click(screen.getByRole("button", { name: "New terminal" }));
    await screen.findByText(/Could not create the terminal/);
    expect(api.terminals.get("starter")).toBeUndefined();
    api.state.failTerminalCreate = false;
    await user.click(screen.getByRole("button", { name: "New terminal" }));
    await screen.findByRole("heading", { name: "Terminal 1" });
  });
});

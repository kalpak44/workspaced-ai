import { RefreshCw } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { terminalSocketUrl } from "../../hooks/useTerminals";
import { IconButton } from "../atoms";

interface Props {
  readonly workspaceId: string;
  readonly terminalId: string;
  readonly running: boolean;
  readonly onDisconnect: () => void;
}

/** Unmounting detaches from the PTY; only the explicit delete action terminates it. */
export function TerminalConsole({ workspaceId, terminalId, running, onDisconnect }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<"connecting" | "connected" | "closed" | "error">("connecting");
  const [attempt, setAttempt] = useState(0);
  const [initiallyRunning] = useState(running);

  useEffect(() => {
    if (!host.current || !initiallyRunning) return;
    let disposed = false;
    let cleanup = () => {};
    setStatus("connecting");
    void Promise.all([import("@xterm/xterm"), import("@xterm/addon-fit")]).then(
      ([{ Terminal: XTerm }, { FitAddon }]) => {
        if (disposed || !host.current) return;
        const terminal = new XTerm({ cursorBlink: true, cursorStyle: "bar", fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace', fontSize: 13, lineHeight: 1.35, scrollback: 5000,
          theme: { background: "#09090b", foreground: "#e4e4e7", cursor: "#fafafa", selectionBackground: "#3f3f4680", black: "#27272a", red: "#f87171", green: "#4ade80", yellow: "#facc15", blue: "#60a5fa", magenta: "#c084fc", cyan: "#22d3ee", white: "#e4e4e7", brightBlack: "#71717a", brightRed: "#fca5a5", brightGreen: "#86efac", brightYellow: "#fde047", brightBlue: "#93c5fd", brightMagenta: "#d8b4fe", brightCyan: "#67e8f9", brightWhite: "#fafafa" } });
        const fit = new FitAddon();
        terminal.loadAddon(fit);
        terminal.open(host.current);
        const socket = new WebSocket(terminalSocketUrl(workspaceId, terminalId));
        socket.binaryType = "arraybuffer";
        const resize = () => {
          if (disposed) return;
          fit.fit();
          if (socket.readyState === WebSocket.OPEN) socket.send(`resize:${terminal.cols}:${terminal.rows}`);
        };
        socket.addEventListener("open", () => {
          if (disposed) return;
          setStatus("connected");
          resize();
          terminal.focus();
        });
        socket.addEventListener("message", (event) => {
          if (!disposed && event.data instanceof ArrayBuffer) terminal.write(new Uint8Array(event.data));
        });
        socket.addEventListener("error", () => { if (!disposed) setStatus("error"); });
        socket.addEventListener("close", (event) => {
          if (disposed) return;
          setStatus(event.code === 1000 ? "closed" : "error");
          terminal.writeln("\r\n\x1b[90m[terminal disconnected]\x1b[0m");
          onDisconnect();
        });
        const input = terminal.onData((data) => { if (socket.readyState === WebSocket.OPEN) socket.send(new TextEncoder().encode(data)); });
        const observer = new ResizeObserver(resize);
        observer.observe(host.current);
        resize();
        cleanup = () => { observer.disconnect(); input.dispose(); socket.close(); terminal.dispose(); };
      },
    ).catch(() => { if (!disposed) setStatus("error"); });
    return () => { disposed = true; cleanup(); };
  }, [workspaceId, terminalId, attempt, initiallyRunning, onDisconnect]);

  return (
    <section aria-label="Workspace terminal" className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-zinc-800 px-3 text-xs text-zinc-400">
        <span role="status" className="flex items-center gap-2"><span className={`size-1.5 rounded-full ${running && status === "connected" ? "bg-emerald-500" : running && status === "connecting" ? "animate-pulse bg-amber-400" : "bg-zinc-500"}`} />{!running ? "Exited" : status === "error" ? "Connection failed" : status === "closed" ? "Disconnected" : status === "connected" ? "Connected" : "Connecting…"}</span>
        {running && (status === "closed" || status === "error") && <IconButton label="Reconnect terminal" title="Reconnect terminal" icon={RefreshCw} onClick={() => setAttempt((value) => value + 1)} />}
      </div>
      {!initiallyRunning && <p className="px-4 pt-4 font-mono text-sm text-zinc-400">This shell has exited. Create a new terminal to continue.</p>}
      <div ref={host} className="min-h-0 min-w-0 flex-1 overflow-hidden p-3" />
    </section>
  );
}

import { RefreshCw, ZoomIn, ZoomOut } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { terminalSocketUrl } from "../../hooks/useTerminals";
import { readStored, writeStored } from "../../lib/storage";
import { IconButton } from "../atoms";

const FONT_SIZE_KEY = "workspaced.terminal.fontSize";
const DEFAULT_FONT_SIZE = 13;
const MIN_FONT_SIZE = 8;
const MAX_FONT_SIZE = 24;

/** The size last chosen in this browser, for every terminal. */
function storedFontSize(): number {
  const stored = Number(readStored(FONT_SIZE_KEY));
  return Number.isInteger(stored) && stored >= MIN_FONT_SIZE && stored <= MAX_FONT_SIZE ? stored : DEFAULT_FONT_SIZE;
}

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
  const [fontSize, setFontSize] = useState(storedFontSize);
  // The open terminal's own controls, for zooming without reconnecting.
  const view = useRef<{ setFontSize: (size: number) => void } | null>(null);

  const zoom = (change: number) => setFontSize((current) => {
    const next = change === 0 ? DEFAULT_FONT_SIZE : Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, current + change));
    writeStored(FONT_SIZE_KEY, String(next));
    return next;
  });

  useEffect(() => {
    view.current?.setFontSize(fontSize);
  }, [fontSize]);

  useEffect(() => {
    if (!host.current || !initiallyRunning) return;
    let disposed = false;
    let cleanup = () => {};
    setStatus("connecting");
    void Promise.all([import("@xterm/xterm"), import("@xterm/addon-fit")]).then(
      ([{ Terminal: XTerm }, { FitAddon }]) => {
        if (disposed || !host.current) return;
        const terminal = new XTerm({ cursorBlink: true, cursorStyle: "bar", fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace', fontSize: storedFontSize(), lineHeight: 1.35, scrollback: 5000,
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
        // Ctrl or Cmd with + and - zooms the terminal, and with 0 resets it, instead of the page.
        terminal.attachCustomKeyEventHandler((event) => {
          if (event.type !== "keydown" || !(event.ctrlKey || event.metaKey) || event.altKey) return true;
          const change = event.key === "=" || event.key === "+" ? 1 : event.key === "-" ? -1 : event.key === "0" ? 0 : null;
          if (change === null) return true;
          event.preventDefault();
          zoom(change);
          return false;
        });
        view.current = { setFontSize: (size) => { terminal.options.fontSize = size; resize(); } };
        const input = terminal.onData((data) => { if (socket.readyState === WebSocket.OPEN) socket.send(new TextEncoder().encode(data)); });
        const observer = new ResizeObserver(resize);
        observer.observe(host.current);
        resize();
        // The cell size is measured with the font as it is now; measure again once it has loaded.
        void document.fonts?.ready.then(resize);
        cleanup = () => { view.current = null; observer.disconnect(); input.dispose(); socket.close(); terminal.dispose(); };
      },
    ).catch(() => { if (!disposed) setStatus("error"); });
    return () => { disposed = true; cleanup(); };
  }, [workspaceId, terminalId, attempt, initiallyRunning, onDisconnect]);

  return (
    <section aria-label="Workspace terminal" className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-zinc-800 px-3 text-xs text-zinc-400">
        <span role="status" className="flex items-center gap-2"><span className={`size-1.5 rounded-full ${running && status === "connected" ? "bg-emerald-500" : running && status === "connecting" ? "animate-pulse bg-amber-400" : "bg-zinc-500"}`} />{!running ? "Exited" : status === "error" ? "Connection failed" : status === "closed" ? "Disconnected" : status === "connected" ? "Connected" : "Connecting…"}</span>
        <div className="flex items-center gap-1">
          {initiallyRunning && <>
            <IconButton label="Smaller text" title="Smaller text (Ctrl −)" icon={ZoomOut} disabled={fontSize <= MIN_FONT_SIZE} onClick={() => zoom(-1)} />
            <button type="button" title="Reset text size (Ctrl 0)" aria-label={`Text size ${fontSize}, reset`} onClick={() => zoom(0)}
              className="min-w-8 rounded px-1 font-mono tabular-nums hover:text-zinc-100">{fontSize}</button>
            <IconButton label="Larger text" title="Larger text (Ctrl +)" icon={ZoomIn} disabled={fontSize >= MAX_FONT_SIZE} onClick={() => zoom(1)} />
          </>}
          {running && (status === "closed" || status === "error") && <IconButton label="Reconnect terminal" title="Reconnect terminal" icon={RefreshCw} onClick={() => setAttempt((value) => value + 1)} />}
        </div>
      </div>
      {!initiallyRunning && <p className="px-4 pt-4 font-mono text-sm text-zinc-400">This shell has exited. Create a new terminal to continue.</p>}
      {/* The padding sits outside the terminal's own box: the fit addon sizes the terminal to all of
          that box, so padding inside it was counted as rows, and the last ones were cut off. */}
      <div className="flex min-h-0 min-w-0 flex-1 p-3">
        <div ref={host} className="min-h-0 min-w-0 flex-1 overflow-hidden" />
      </div>
    </section>
  );
}

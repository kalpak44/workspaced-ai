import { useEffect, useRef, useState } from "react";
import { navigate } from "../../hooks/useRoute";
import type { Workspace } from "../../hooks/useWorkspaces";
import { PageHeader } from "../molecules";
import { WorkspaceStatusView } from "./WorkspaceStatusView";

interface Props {
  readonly workspace: Workspace;
  readonly deleting: boolean;
}

type ConnectionStatus = "connecting" | "connected" | "closed";

function socketUrl(workspaceId: string): string {
  const protocol = globalThis.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${globalThis.location.host}/api/v1/workspaces/${workspaceId}/terminal`;
}

/** An xterm.js TTY connected to Bash inside this workspace's pod. */
export function TerminalPage({ workspace, deleting }: Props) {
  const ready = workspace.status === "READY" && !deleting;
  const host = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<ConnectionStatus>("connecting");

  useEffect(() => {
    if (!ready || !host.current) {
      return undefined;
    }
    let disposed = false;
    let dispose = () => {};
    void Promise.all([import("@xterm/xterm"), import("@xterm/addon-fit")]).then(
      ([{ Terminal: XTerm }, { FitAddon }]) => {
        if (disposed || !host.current) {
          return;
        }
        const terminal = new XTerm({
          cursorBlink: true,
          cursorStyle: "bar",
          fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace',
          fontSize: 13,
          lineHeight: 1.35,
          scrollback: 5000,
          theme: {
            background: "#09090b",
            foreground: "#e4e4e7",
            cursor: "#fafafa",
            cursorAccent: "#09090b",
            selectionBackground: "#3f3f4680",
            black: "#27272a",
            red: "#f87171",
            green: "#4ade80",
            yellow: "#facc15",
            blue: "#60a5fa",
            magenta: "#c084fc",
            cyan: "#22d3ee",
            white: "#e4e4e7",
            brightBlack: "#71717a",
            brightRed: "#fca5a5",
            brightGreen: "#86efac",
            brightYellow: "#fde047",
            brightBlue: "#93c5fd",
            brightMagenta: "#d8b4fe",
            brightCyan: "#67e8f9",
            brightWhite: "#fafafa",
          },
        });
        const fit = new FitAddon();
        terminal.loadAddon(fit);
        terminal.open(host.current);

        const socket = new WebSocket(socketUrl(workspace.id));
        socket.binaryType = "arraybuffer";
        const resize = () => {
          fit.fit();
          if (socket.readyState === WebSocket.OPEN) {
            socket.send(`resize:${terminal.cols}:${terminal.rows}`);
          }
        };
        socket.addEventListener("open", () => {
          setStatus("connected");
          resize();
          terminal.focus();
        });
        socket.addEventListener("message", (event) => {
          if (event.data instanceof ArrayBuffer) {
            terminal.write(new Uint8Array(event.data));
          }
        });
        socket.addEventListener("close", (event) => {
          setStatus("closed");
          terminal.writeln(
            `\r\n\x1b[90m[terminal closed${event.reason ? `: ${event.reason}` : ""}]\x1b[0m`,
          );
        });
        const input = terminal.onData((data) => {
          if (socket.readyState === WebSocket.OPEN) {
            socket.send(new TextEncoder().encode(data));
          }
        });
        const observer = new ResizeObserver(resize);
        observer.observe(host.current);
        resize();
        dispose = () => {
          observer.disconnect();
          input.dispose();
          socket.close();
          terminal.dispose();
        };
      },
    );

    return () => {
      disposed = true;
      dispose();
    };
  }, [ready, workspace.id]);

  if (!ready) {
    return <WorkspaceStatusView workspace={workspace} deleting={deleting} />;
  }

  return (
    <>
      <PageHeader
        title="Terminal"
        crumbs={[
          {
            label: workspace.name,
            onClick: () => navigate({ name: "workspace", workspaceId: workspace.id }),
          },
        ]}
        badge={
          <span className="ml-1 flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
            <span
              className={`size-1.5 rounded-full ${
                status === "connected"
                  ? "bg-emerald-500"
                  : status === "closed"
                    ? "bg-rose-500"
                    : "animate-pulse bg-amber-400"
              }`}
            />
            {status}
          </span>
        }
      />
      <section aria-label="Workspace terminal" className="flex min-h-0 flex-1 bg-zinc-950 p-3 sm:p-4">
        <div
          ref={host}
          className="min-h-0 min-w-0 flex-1 overflow-hidden rounded-md border border-zinc-800 bg-zinc-950 p-3 shadow-[0_18px_60px_-30px_rgba(0,0,0,0.8)]"
        />
      </section>
    </>
  );
}

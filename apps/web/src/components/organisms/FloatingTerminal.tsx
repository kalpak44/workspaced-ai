import { PanelBottomClose, X } from "lucide-react";
import { useRef, useState, type PointerEvent } from "react";
import { closeDetachedTerminal, setDetachedTerminal, useDetachedTerminal } from "../../hooks/useDetachedTerminal";
import { useIsMobile } from "../../hooks/useIsMobile";
import { navigate } from "../../hooks/useRoute";
import { Button, IconButton } from "../atoms";
import { TerminalConsole } from "./TerminalConsole";

const WIDTH = 640;
const HEIGHT = 380;
const MARGIN = 16;
// The window's list of shells polls on its page; here a closed shell only shows as disconnected.
const ignoreDisconnect = () => {};

/** Keeps the window's title bar on screen, so it can always be dragged back. */
function clamp(x: number, y: number) {
  return {
    x: Math.min(Math.max(x, MARGIN - WIDTH + 120), globalThis.innerWidth - 120),
    y: Math.min(Math.max(y, MARGIN), globalThis.innerHeight - 48),
  };
}

/**
 * The detached terminal, a window over every page that can be dragged by its title bar and
 * resized from its corner. Desktop only: a phone has no room beside the page for it.
 */
export function FloatingTerminal() {
  const detached = useDetachedTerminal();
  const mobile = useIsMobile();
  const [position, setPosition] = useState(() => clamp(globalThis.innerWidth - WIDTH - MARGIN, globalThis.innerHeight - HEIGHT - MARGIN));
  // Closing deletes the shell, so it asks first; then "deleting", or "failed" when the delete did not go through.
  const [closing, setClosing] = useState<"asking" | "deleting" | "failed" | null>(null);
  const drag = useRef<{ dx: number; dy: number } | null>(null);

  if (!detached || mobile) return null;

  const start = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || (event.target as HTMLElement).closest("button")) return;
    drag.current = { dx: event.clientX - position.x, dy: event.clientY - position.y };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const move = (event: PointerEvent<HTMLDivElement>) => {
    if (drag.current) setPosition(clamp(event.clientX - drag.current.dx, event.clientY - drag.current.dy));
  };
  const stop = () => { drag.current = null; };
  const close = async () => {
    setClosing("deleting");
    setClosing(await closeDetachedTerminal() ? null : "failed");
  };
  const attach = () => {
    setDetachedTerminal(null);
    navigate({ name: "terminal", workspaceId: detached.workspaceId, terminalId: detached.terminalId });
  };

  return (
    <div role="dialog" aria-label={`Detached terminal: ${detached.title}`}
      className="fixed z-40 flex resize flex-col overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950 shadow-2xl"
      style={{ left: position.x, top: position.y, width: WIDTH, height: HEIGHT, minWidth: 320, minHeight: 200, maxWidth: "calc(100vw - 32px)", maxHeight: "calc(100dvh - 32px)" }}>
      <div onPointerDown={start} onPointerMove={move} onPointerUp={stop} onPointerCancel={stop}
        className="flex h-9 shrink-0 cursor-move items-center justify-between gap-2 border-b border-zinc-800 pr-1 pl-3 text-xs text-zinc-300 select-none">
        <span className="truncate"><span className="font-medium text-zinc-100">{detached.title}</span> · {detached.workspaceName}</span>
        <span className="flex shrink-0 items-center gap-0.5">
          <IconButton label="Attach terminal to its page" title="Attach to page" icon={PanelBottomClose} onClick={attach} />
          <IconButton label="Close and delete terminal" title="Close and delete terminal" icon={X} disabled={closing === "deleting"} onClick={() => setClosing("asking")} />
        </span>
      </div>
      {closing && <div role="alert" className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-zinc-800 bg-zinc-900 px-3 py-2 text-xs text-zinc-300">
        <span>{closing === "failed" ? "Could not delete the terminal. It is still running." : "Delete this terminal? Commands running in it stop."}</span>
        <span className="flex gap-2">
          <Button label="Cancel" variant="secondary" disabled={closing === "deleting"} onClick={() => setClosing(null)} />
          <Button label="Delete terminal" variant="danger" busy={closing === "deleting"} onClick={() => { void close(); }} />
        </span>
      </div>}
      <div className="flex min-h-0 flex-1 [&>section]:rounded-none [&>section]:border-0">
        <TerminalConsole key={`${detached.workspaceId}/${detached.terminalId}`} workspaceId={detached.workspaceId} terminalId={detached.terminalId} running onDisconnect={ignoreDisconnect} />
      </div>
    </div>
  );
}

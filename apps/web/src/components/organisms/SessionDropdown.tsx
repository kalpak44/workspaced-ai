import { CalendarClock, ChevronDown, MessageSquare } from "lucide-react";
import { useCallback, useId, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import type { Session } from "../../hooks/useSessions";
import { SessionNavigation } from "./SessionNavigation";

interface Props {
  readonly sessions: readonly Session[];
  readonly selectedId?: string;
  readonly loading: boolean;
  readonly deleting: ReadonlySet<string>;
  readonly onOpen: (id: string) => void;
  readonly onDelete: (id: string) => void;
}

/** The same compact, searchable session picker on both mobile conversation screens. */
export function SessionDropdown({ sessions, selectedId, loading, deleting, onOpen, onDelete }: Props) {
  const [open, setOpen] = useState(false);
  const navigation = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const listId = useId();
  const close = useCallback(() => setOpen(false), []);
  useDismiss(navigation, open, close);
  const selected = sessions.find((session) => session.id === selectedId);

  return (
    <div ref={navigation} className="relative min-w-0 flex-1">
        <button ref={trigger} type="button" aria-label="Choose session" aria-expanded={open} aria-controls={listId}
          onClick={() => setOpen(!open)}
          className="flex min-h-11 w-full min-w-0 items-center gap-2 rounded-md border border-zinc-300 px-3 text-sm dark:border-zinc-700">
          {selected?.task ? <CalendarClock aria-hidden className="size-4 shrink-0" /> : <MessageSquare aria-hidden className="size-4 shrink-0" />}
          <span className="min-w-0 flex-1 truncate text-left">{loading ? "Loading sessions…" : selectedId ? selected?.title ?? "New session" : "Recent sessions"}</span>
          <ChevronDown aria-hidden className="size-4 shrink-0" />
        </button>
        {open && <div id={listId} onKeyDown={(event) => {
          if (event.key === "Escape") { close(); trigger.current?.focus(); }
        }} role="region" aria-label="Session picker" className="absolute left-0 top-full z-20 mt-2 flex max-h-[min(32rem,60dvh)] w-[calc(100vw-1.5rem)] max-w-sm flex-col rounded-lg border border-zinc-200 bg-white p-3 shadow-xl dark:border-zinc-700 dark:bg-zinc-900">
          <SessionNavigation sessions={sessions} selectedId={selectedId} loading={loading} deleting={deleting} autoFocus
            onOpen={(id) => { close(); trigger.current?.focus(); onOpen(id); }}
            onDelete={(id) => { close(); trigger.current?.focus(); onDelete(id); }} />
        </div>}
    </div>
  );
}

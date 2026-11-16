import {
  Activity,
  Bell,
  CalendarClock,
  Clock,
  CornerDownLeft,
  FileText,
  Globe,
  KeyRound,
  Layers,
  MessageSquare,
  Monitor,
  Moon,
  Plug,
  Plus,
  Search,
  Settings,
  Shield,
  SquareTerminal,
  Store,
  Sun,
  type LucideIcon,
  Variable,
  Wallet,
} from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { navigate, type Route } from "../../hooks/useRoute";
import type { Theme } from "../../hooks/useTheme";
import type { Workspace } from "../../hooks/useWorkspaces";

interface Command {
  readonly id: string;
  readonly group: string;
  readonly label: string;
  /** Extra words a search matches, e.g. "metrics" for Monitoring. */
  readonly keywords?: string;
  readonly hint?: string;
  readonly icon: LucideIcon;
  readonly run: () => void;
}

interface Props {
  readonly open: boolean;
  readonly onOpenChange: (open: boolean) => void;
  readonly workspaces: readonly Workspace[];
  /** The workspace the page is about, whose tools are offered first. */
  readonly current: Workspace | null;
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
  readonly onNewWorkspace: () => void;
}

const SETTINGS_SECTIONS: ReadonlyArray<readonly [string, string, LucideIcon, string]> = [
  ["mcp", "MCP servers", Plug, "integrations tools"],
  ["secrets", "Secrets", KeyRound, "tokens keys env"],
  ["variables", "Variables", Variable, "environment env"],
  ["sessions", "Session cleanup", Clock, "ttl delete inactivity"],
  ["firewall", "Firewall", Shield, "ports open public ip address"],
  ["instructions", "Instructions", FileText, "agents prompt rules"],
  ["tasks", "Scheduled tasks", CalendarClock, "cron schedule"],
];

/** Lower is better; null when the query does not match at all. */
function rank(command: Command, query: string): number | null {
  if (query === "") return 0;
  const label = command.label.toLowerCase();
  if (label.startsWith(query)) return 0;
  if (label.split(/\s+/).some((word) => word.startsWith(query))) return 1;
  if (label.includes(query)) return 2;
  const haystack = `${command.group} ${command.keywords ?? ""}`.toLowerCase();
  if (query.split(/\s+/).every((word) => label.includes(word) || haystack.includes(word))) return 3;
  return null;
}

/**
 * Jump anywhere and run common actions from the keyboard: ⌘K or Ctrl+K opens it from any page,
 * arrows move, Enter runs, Escape closes. The same list a mouse user reaches through menus.
 */
export function CommandPalette({ open, onOpenChange, workspaces, current, theme, onThemeChange, onNewWorkspace }: Props) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const listId = useId();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        onOpenChange(!open);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onOpenChange]);

  // Each opening starts clean, with focus in the search box.
  useEffect(() => {
    if (!open) return undefined;
    setQuery("");
    setActive(0);
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    input.current?.focus();
    return () => {
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [open]);

  const commands = useMemo<Command[]>(() => {
    const go = (route: Route) => () => navigate(route);
    const own: Command[] = current
      ? [
          { id: "ws-sessions", group: current.name, label: "Sessions", keywords: "chat new task", icon: MessageSquare, run: go({ name: "workspace", workspaceId: current.id }) },
          { id: "ws-terminal", group: current.name, label: "Terminal", keywords: "shell console", icon: SquareTerminal, run: go({ name: "terminal", workspaceId: current.id }) },
          { id: "ws-monitoring", group: current.name, label: "Monitoring", keywords: "metrics cpu memory disk network", icon: Activity, run: go({ name: "monitoring", workspaceId: current.id }) },
          ...SETTINGS_SECTIONS.map(([section, label, icon, keywords]): Command => ({
            id: `ws-settings-${section}`, group: current.name, label, hint: "Settings", keywords: `settings ${keywords}`, icon,
            run: go({ name: "settings", workspaceId: current.id, section }),
          })),
        ]
      : [];
    return [
      ...own,
      ...workspaces
        .filter((workspace) => workspace.id !== current?.id)
        .map((workspace): Command => ({
          id: `open-${workspace.id}`, group: "Workspaces", label: workspace.name, hint: "Open", keywords: "workspace switch", icon: Layers,
          run: go({ name: "workspace", workspaceId: workspace.id }),
        })),
      { id: "new-workspace", group: "Actions", label: "New workspace", keywords: "create add", icon: Plus, run: onNewWorkspace },
      ...(["light", "dark", "system"] as const)
        .filter((option) => option !== theme)
        .map((option): Command => ({
          id: `theme-${option}`, group: "Actions", label: `Use ${option} theme`, keywords: "appearance mode colour color",
          icon: option === "light" ? Sun : option === "dark" ? Moon : Monitor, run: () => onThemeChange(option),
        })),
      { id: "store", group: "Go to", label: "Recipe store", keywords: "recipes templates", icon: Store, run: go({ name: "store" }) },
      { id: "notifications", group: "Go to", label: "Notifications", keywords: "inbox alerts", icon: Bell, run: go({ name: "notifications" }) },
      { id: "billing", group: "Go to", label: "Billing", keywords: "balance invoices funds payment", icon: Wallet, run: go({ name: "billing" }) },
      { id: "domains", group: "Go to", label: "Domains", keywords: "buy dns", icon: Globe, run: go({ name: "domains" }) },
      { id: "settings", group: "Go to", label: "Account settings", keywords: "profile password users", icon: Settings, run: go({ name: "general-settings" }) },
    ];
  }, [current, workspaces, theme, onThemeChange, onNewWorkspace]);

  const normalised = query.trim().toLowerCase();
  const results = commands
    .map((command, order) => ({ command, order, score: rank(command, normalised) }))
    .filter((entry): entry is { command: Command; order: number; score: number } => entry.score !== null)
    .sort((a, b) => (normalised === "" ? a.order - b.order : a.score - b.score || a.order - b.order))
    .map((entry) => entry.command);
  const selected = Math.min(active, Math.max(0, results.length - 1));

  useEffect(() => {
    list.current?.querySelector(`[data-index="${selected}"]`)?.scrollIntoView?.({ block: "nearest" });
  }, [selected]);

  if (!open) return null;

  const run = (command: Command | undefined) => {
    if (!command) return;
    onOpenChange(false);
    command.run();
  };

  let lastGroup = "";
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-start justify-center px-3 pt-[10dvh] sm:pt-[14dvh]">
      <button type="button" aria-label="Close command palette" tabIndex={-1} onClick={() => onOpenChange(false)}
        className="absolute inset-0 animate-fade-in cursor-default bg-zinc-950/50 backdrop-blur-sm" />
      <div role="dialog" aria-modal="true" aria-label="Command palette"
        className="relative flex max-h-[70dvh] w-full max-w-xl animate-pop-in flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white text-zinc-900 shadow-2xl dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100">
        <div className="flex items-center gap-3 border-b border-zinc-200 px-4 dark:border-zinc-800">
          <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
          <input ref={input} role="combobox" aria-expanded="true" aria-controls={listId} aria-autocomplete="list"
            aria-activedescendant={results.length > 0 ? `${listId}-${selected}` : undefined}
            aria-label="Search commands" placeholder="Search workspaces, pages and actions…" value={query}
            onChange={(event) => { setQuery(event.target.value); setActive(0); }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown") { event.preventDefault(); setActive((selected + 1) % Math.max(1, results.length)); }
              else if (event.key === "ArrowUp") { event.preventDefault(); setActive((selected - 1 + results.length) % Math.max(1, results.length)); }
              else if (event.key === "Enter") { event.preventDefault(); run(results[selected]); }
              else if (event.key === "Escape") { event.preventDefault(); onOpenChange(false); }
            }}
            className="h-13 w-full min-w-0 bg-transparent text-base outline-none placeholder:text-zinc-400 sm:text-sm" />
          <kbd className="hidden shrink-0 rounded border border-zinc-200 px-1.5 py-0.5 font-sans text-[11px] text-zinc-500 sm:block dark:border-zinc-700 dark:text-zinc-400">Esc</kbd>
        </div>
        <ul ref={list} id={listId} role="listbox" aria-label="Commands" className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
          {results.map((command, index) => {
            const heading = command.group !== lastGroup ? command.group : null;
            lastGroup = command.group;
            const Icon = command.icon;
            return (
              <li key={command.id} role="presentation">
                {heading && <p role="presentation" className="px-2 pt-2 pb-1 text-[11px] font-medium tracking-wide text-zinc-400 uppercase first:pt-0.5">{heading}</p>}
                <button id={`${listId}-${index}`} type="button" role="option" aria-selected={index === selected} data-index={index}
                  tabIndex={-1} onMouseMove={() => setActive(index)} onClick={() => run(command)}
                  className="flex w-full cursor-pointer items-center gap-3 rounded-lg border-0 bg-transparent px-2 py-2 text-left text-sm aria-selected:bg-zinc-100 dark:aria-selected:bg-zinc-800">
                  <span className="flex size-7 shrink-0 items-center justify-center rounded-md border border-zinc-200 bg-white text-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-400">
                    <Icon aria-hidden className="size-3.5" />
                  </span>
                  <span className="min-w-0 flex-1 truncate">{command.label}</span>
                  {command.hint && <span className="shrink-0 text-xs text-zinc-400">{command.hint}</span>}
                  {index === selected && <CornerDownLeft aria-hidden className="size-3.5 shrink-0 text-zinc-400" />}
                </button>
              </li>
            );
          })}
          {results.length === 0 && <li className="px-3 py-10 text-center text-sm text-zinc-500 dark:text-zinc-400">Nothing matches “{query.trim()}”.</li>}
        </ul>
        <div className="hidden items-center gap-4 border-t border-zinc-200 bg-zinc-50 px-4 py-2 text-[11px] text-zinc-500 sm:flex dark:border-zinc-800 dark:bg-zinc-950/40 dark:text-zinc-400">
          <span><kbd className="font-sans">↑↓</kbd> to move</span>
          <span><kbd className="font-sans">↵</kbd> to open</span>
          <span className="ml-auto"><kbd className="font-sans">⌘K</kbd> anywhere</span>
        </div>
      </div>
    </div>,
    document.body,
  );
}

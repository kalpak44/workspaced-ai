import { LayoutGrid, Plus, Search, Store } from "lucide-react";
import { navigate } from "../../hooks/useRoute";
import type { Theme } from "../../hooks/useTheme";
import { Divider, Logo } from "../atoms";
import { NotificationMenu } from "./NotificationMenu";
import { UserMenu } from "./UserMenu";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
  /** Marks the store link as the current page. */
  readonly storeOpen?: boolean;
  /** Opens the command palette; the bar shows its shortcut so it can be learned. */
  readonly onSearch?: () => void;
  /** Marks the workspaces link as the current page. */
  readonly workspacesOpen?: boolean;
  /** Creating a workspace is the app's main action, so it is a click away from every page. */
  readonly onNewWorkspace?: () => void;
}

const LINK = "flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";
const LINK_ON = "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100";
const LINK_OFF = "text-zinc-500 dark:text-zinc-400";

// Macs label the shortcut with ⌘, everything else with Ctrl.
const MAC = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

export function TopBar({ theme, onThemeChange, storeOpen = false, onSearch, workspacesOpen = false, onNewWorkspace }: Props) {
  return (
    <header className="relative z-30 flex h-12 shrink-0 items-center justify-between border-b border-zinc-200 bg-white px-3 sm:px-4 dark:border-zinc-800 dark:bg-zinc-950">
      <a href="/" aria-label="Go to home" className="flex items-center gap-2 rounded-md px-1 py-1 transition-opacity hover:opacity-80">
        <Logo />
        <span aria-hidden className="hidden text-sm font-semibold tracking-tight sm:inline">workspaced<span className="text-zinc-400">-ai</span></span>
      </a>
      <div className="flex items-center gap-1.5 sm:gap-2">
        {onSearch && (
          <button type="button" aria-label="Open command palette" aria-keyshortcuts={MAC ? "Meta+K" : "Control+K"} onClick={onSearch}
            className="mr-1 hidden h-8 w-56 items-center lg:w-64 gap-2 rounded-lg border border-zinc-200 bg-zinc-50 px-2.5 text-sm text-zinc-400 transition-colors hover:border-zinc-300 hover:text-zinc-600 md:flex dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-zinc-700 dark:hover:text-zinc-300">
            <Search aria-hidden className="size-3.5" />
            <span className="flex-1 text-left">Jump to…</span>
            <kbd className="rounded border border-zinc-200 bg-white px-1.5 font-sans text-[11px] text-zinc-500 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-400">{MAC ? "⌘K" : "Ctrl K"}</kbd>
          </button>
        )}
        {onSearch && (
          <button type="button" aria-label="Jump to" title="Jump to…" onClick={onSearch}
            className="rounded-md p-1.5 text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-900 md:hidden dark:hover:bg-zinc-800 dark:hover:text-zinc-100">
            <Search aria-hidden className="size-4" />
          </button>
        )}
        <a
          href="/"
          aria-current={workspacesOpen ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            navigate({ name: "home" });
          }}
          className={`${LINK} ${workspacesOpen ? LINK_ON : LINK_OFF}`}
        >
          <LayoutGrid aria-hidden className="size-4" />
          <span className="max-sm:sr-only">Workspaces</span>
        </a>
        <a
          href="/store"
          aria-current={storeOpen ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            navigate({ name: "store" });
          }}
          className={`${LINK} ${storeOpen ? LINK_ON : LINK_OFF}`}
        >
          <Store aria-hidden className="size-4" />
          <span className="max-sm:sr-only">Store</span>
        </a>
        {onNewWorkspace && (
          <button type="button" aria-label="New workspace" title="New workspace" onClick={onNewWorkspace}
            className="flex items-center gap-1.5 rounded-md p-1.5 text-sm font-medium text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-900 sm:rounded-lg sm:bg-zinc-900 sm:px-2.5 sm:text-white sm:shadow-sm sm:hover:bg-zinc-700 sm:hover:text-white dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100 sm:dark:bg-white sm:dark:text-zinc-900 sm:dark:hover:bg-zinc-200 sm:dark:hover:text-zinc-900">
            <Plus aria-hidden className="size-4" /><span aria-hidden className="hidden lg:inline">New workspace</span>
          </button>
        )}
        <NotificationMenu />
        <Divider />
        <UserMenu theme={theme} onThemeChange={onThemeChange} />
      </div>
    </header>
  );
}

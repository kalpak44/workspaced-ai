import { Bell, Store } from "lucide-react";
import { navigate } from "../../hooks/useRoute";
import type { Theme } from "../../hooks/useTheme";
import { Divider, IconButton, Logo } from "../atoms";
import { UserMenu } from "./UserMenu";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
  /** Marks the store link as the current page. */
  readonly storeOpen?: boolean;
}

export function TopBar({ theme, onThemeChange, storeOpen = false }: Props) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between border-b border-zinc-200 px-4 dark:border-zinc-800">
      <a href="/" aria-label="Go to home" className="rounded focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-zinc-500">
        <Logo />
      </a>
      <div className="flex items-center gap-3">
        <a
          href="/store"
          aria-current={storeOpen ? "page" : undefined}
          onClick={(event) => {
            event.preventDefault();
            navigate({ name: "store" });
          }}
          className={`flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100 ${storeOpen ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-500 dark:text-zinc-400"}`}
        >
          <Store aria-hidden className="size-4" />
          <span className="max-sm:sr-only">Store</span>
        </a>
        <IconButton label="Notifications" icon={Bell} />
        <Divider />
        <UserMenu theme={theme} onThemeChange={onThemeChange} />
      </div>
    </header>
  );
}

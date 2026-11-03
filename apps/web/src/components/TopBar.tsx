import { Bell } from "lucide-react";
import type { Theme } from "../useTheme";
import { Logo } from "./Logo";
import { UserMenu } from "./UserMenu";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
}

export function TopBar({ theme, onThemeChange }: Props) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between border-b border-zinc-200 px-4 dark:border-zinc-800">
      <Logo />
      <div className="flex items-center gap-3">
        <button
          type="button"
          aria-label="Notifications"
          className="rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
        >
          <Bell className="size-4" />
        </button>
        <span className="h-6 w-px bg-zinc-200 dark:bg-zinc-800" />
        <UserMenu theme={theme} onThemeChange={onThemeChange} />
      </div>
    </header>
  );
}

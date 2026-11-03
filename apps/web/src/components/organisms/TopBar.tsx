import { Bell } from "lucide-react";
import type { Theme } from "../../hooks/useTheme";
import { Divider, IconButton, Logo } from "../atoms";
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
        <IconButton label="Notifications" icon={Bell} />
        <Divider />
        <UserMenu theme={theme} onThemeChange={onThemeChange} />
      </div>
    </header>
  );
}

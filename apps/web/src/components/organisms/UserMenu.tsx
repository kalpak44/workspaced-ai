import { ChevronsUpDown, LogOut } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import type { Theme } from "../../hooks/useTheme";
import { Avatar } from "../atoms";
import { Menu, MenuItem, MenuLink, ThemeSwitch } from "../molecules";

const ROW = "flex w-full items-center justify-between px-4 py-2.5";
const SEPARATED = "border-b border-zinc-200 dark:border-zinc-700";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
}

export function UserMenu({ theme, onThemeChange }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open, close);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-label="Account menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1 rounded-md p-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
      >
        <Avatar />
        <ChevronsUpDown className="size-3.5 text-zinc-400" />
      </button>
      {open && (
        <Menu className="mt-2 w-72 rounded-lg shadow-xl">
          <div className={ROW}>
            <span>Version</span>
            <span className="text-emerald-500">{import.meta.env.VITE_APP_VERSION ?? "dev"}</span>
          </div>
          <MenuItem className={ROW} onClick={close}>
            Settings
          </MenuItem>
          <MenuLink className={`${ROW} ${SEPARATED}`} href="/api/v1/swagger-ui">
            Swagger
          </MenuLink>
          <div className={`${ROW} ${SEPARATED}`}>
            <span>Theme</span>
            <ThemeSwitch theme={theme} onChange={onThemeChange} />
          </div>
          <MenuItem className={ROW} onClick={close}>
            Logout
            <LogOut className="size-4" />
          </MenuItem>
        </Menu>
      )}
    </div>
  );
}

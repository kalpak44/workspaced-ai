import { ChevronsUpDown, LogOut } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useIsMobile } from "../../hooks/useIsMobile";
import { useDismiss } from "../../hooks/useDismiss";
import { useGeneralSettings } from "../../hooks/useGeneralSettings";
import { navigate } from "../../hooks/useRoute";
import type { Theme } from "../../hooks/useTheme";
import { Avatar } from "../atoms";
import { Menu, MenuItem, MenuLink, Modal, ModalBody, ThemeSwitch } from "../molecules";

const ROW = "flex w-full items-center justify-between px-4 py-2.5";
const SEPARATED = "border-b border-zinc-200 dark:border-zinc-700";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
}

export function UserMenu({ theme, onThemeChange }: Props) {
  const { settings } = useGeneralSettings();
  const [open, setOpen] = useState(false);
  const mobile = useIsMobile();
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open && !mobile, close);

  const items = <>
          <div className={ROW}>
            <span>Version</span>
            <span className="text-emerald-500">{import.meta.env.VITE_APP_VERSION ?? "dev"}</span>
          </div>
          <MenuItem className={ROW} onClick={() => { close(); navigate({ name: "general-settings" }); }}>
            Settings
          </MenuItem>
          <MenuLink className={`${ROW} ${SEPARATED}`} href="/api/v1/swagger-ui">
            API Reference
          </MenuLink>
          <div className={`${ROW} ${SEPARATED}`}>
            <span>Theme</span>
            <ThemeSwitch theme={theme} onChange={onThemeChange} />
          </div>
          <MenuItem className={ROW} onClick={close}>
            Logout
            <LogOut className="size-4" />
          </MenuItem>
  </>;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-label="Account menu"
        aria-expanded={open}
        aria-haspopup={mobile ? "dialog" : "menu"}
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1 rounded-md p-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
      >
        <Avatar src={settings.avatar} />
        <ChevronsUpDown className="size-3.5 text-zinc-400" />
      </button>
      {open && (mobile ? (
        <Modal title="Account" onClose={close}>
          <ModalBody><div role="menu" aria-label="Account menu" className="text-sm">{items}</div></ModalBody>
        </Modal>
      ) : (
        <Menu className="mt-2 w-72 rounded-lg shadow-xl">{items}</Menu>
      ))}
    </div>
  );
}

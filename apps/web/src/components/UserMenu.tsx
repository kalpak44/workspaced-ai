import { ChevronsUpDown, LogOut, Monitor, Moon, Sun } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { type ApiStatus, fetchApiStatus } from "../apiStatus";
import { useDismiss } from "../useDismiss";
import type { Theme } from "../useTheme";

const THEMES: ReadonlyArray<{ value: Theme; label: string; Icon: typeof Sun }> = [
  { value: "light", label: "Light", Icon: Sun },
  { value: "dark", label: "Dark", Icon: Moon },
  { value: "system", label: "System", Icon: Monitor },
];

const STATUS_COLOR: Record<ApiStatus, string> = {
  checking: "text-zinc-400",
  ready: "text-emerald-500",
  unavailable: "text-amber-500",
  unreachable: "text-rose-500",
};

const ROW = "flex w-full items-center justify-between px-4 py-2.5 text-left";
const HOVER = "hover:bg-zinc-100 dark:hover:bg-zinc-800";

interface Props {
  readonly theme: Theme;
  readonly onThemeChange: (theme: Theme) => void;
}

export function UserMenu({ theme, onThemeChange }: Props) {
  const [open, setOpen] = useState(false);
  const [api, setApi] = useState<ApiStatus>("checking");
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open, close);

  useEffect(() => {
    if (open) {
      setApi("checking");
      fetchApiStatus().then(setApi, () => setApi("unreachable"));
    }
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-label="Account menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1 rounded-md p-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"
      >
        <span className="size-6 rounded-full bg-linear-to-br from-sky-400 via-fuchsia-500 to-orange-400" />
        <ChevronsUpDown className="size-3.5 text-zinc-400" />
      </button>
      {open && (
        <div
          role="menu"
          className="absolute top-full right-0 z-10 mt-2 w-72 overflow-hidden rounded-lg border border-zinc-200 bg-white text-sm shadow-xl dark:border-zinc-700 dark:bg-zinc-900"
        >
          <div className={ROW}>
            <span>Version</span>
            <span className="text-emerald-500">{import.meta.env.VITE_APP_VERSION ?? "dev"}</span>
          </div>
          <div className={`${ROW} border-b border-zinc-200 dark:border-zinc-700`}>
            <span>API</span>
            <span className={STATUS_COLOR[api]}>{api}</span>
          </div>
          <button type="button" role="menuitem" className={`${ROW} ${HOVER}`} onClick={close}>
            Settings
          </button>
          <a
            role="menuitem"
            href="/api/v1/swagger-ui"
            target="_blank"
            rel="noreferrer"
            className={`${ROW} ${HOVER} border-b border-zinc-200 dark:border-zinc-700`}
          >
            Docs
          </a>
          <div className={`${ROW} border-b border-zinc-200 dark:border-zinc-700`}>
            <span>Theme</span>
            <div className="flex rounded-full border border-zinc-200 p-0.5 dark:border-zinc-700">
              {THEMES.map(({ value, label, Icon }) => (
                <button
                  key={value}
                  type="button"
                  aria-label={label}
                  aria-pressed={theme === value}
                  onClick={() => onThemeChange(value)}
                  className={`rounded-full p-1 ${
                    theme === value
                      ? "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900"
                      : "text-zinc-500"
                  }`}
                >
                  <Icon className="size-3.5" />
                </button>
              ))}
            </div>
          </div>
          <button type="button" role="menuitem" className={`${ROW} ${HOVER}`} onClick={close}>
            Logout
            <LogOut className="size-4" />
          </button>
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from "react";
import { readStored, writeStored } from "./storage";

export type Theme = "light" | "dark" | "system";

const KEY = "workspaced.theme";
const DARK_QUERY = "(prefers-color-scheme: dark)";

function storedTheme(): Theme {
  const value = readStored(KEY);
  return value === "light" || value === "dark" ? value : "system";
}

function prefersDark(): boolean {
  return globalThis.matchMedia?.(DARK_QUERY).matches ?? true;
}

export function useTheme(): [Theme, (theme: Theme) => void] {
  const [theme, setTheme] = useState<Theme>(storedTheme);

  useEffect(() => {
    const apply = () => {
      const dark = theme === "dark" || (theme === "system" && prefersDark());
      document.documentElement.classList.toggle("dark", dark);
    };
    apply();
    writeStored(KEY, theme);
    if (theme !== "system" || !globalThis.matchMedia) {
      return undefined;
    }
    // Follow the OS while "system" is chosen, not only at the moment it was picked.
    const query = globalThis.matchMedia(DARK_QUERY);
    query.addEventListener("change", apply);
    return () => query.removeEventListener("change", apply);
  }, [theme]);

  return [theme, setTheme];
}

import { Monitor, Moon, Sun } from "lucide-react";
import type { Theme } from "../../hooks/useTheme";

const THEMES: ReadonlyArray<{ value: Theme; label: string; Icon: typeof Sun }> = [
  { value: "light", label: "Light", Icon: Sun },
  { value: "dark", label: "Dark", Icon: Moon },
  { value: "system", label: "System", Icon: Monitor },
];

interface Props {
  readonly theme: Theme;
  readonly onChange: (theme: Theme) => void;
}

export function ThemeSwitch({ theme, onChange }: Props) {
  return (
    <div className="flex rounded-full border border-zinc-200 p-0.5 dark:border-zinc-700">
      {THEMES.map(({ value, label, Icon }) => (
        <button
          key={value}
          type="button"
          aria-label={label}
          aria-pressed={theme === value}
          onClick={() => onChange(value)}
          className={`inline-flex size-11 items-center justify-center rounded-full p-1 sm:size-auto ${
            theme === value
              ? "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900"
              : "text-zinc-500"
          }`}
        >
          <Icon className="size-3.5" />
        </button>
      ))}
    </div>
  );
}

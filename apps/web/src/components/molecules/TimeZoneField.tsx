import { Clock } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { TIME_ZONES, zoneOffset } from "../../lib/timeZones";
import { Select } from "../atoms";

interface Props {
  readonly id: string;
  readonly value: string;
  readonly disabled?: boolean;
  readonly onChange: (zone: string) => void;
}

/** The zone picker, with a clock showing the time there so the choice can be checked. */
export function TimeZoneField({ id, value, disabled = false, onChange }: Props) {
  const [now, setNow] = useState(() => new Date());
  // The preview is a clock, so it keeps time while the page is open.
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(timer);
  }, []);
  // Offsets are worked out once per visit; recomputing hundreds on every tick is wasted work.
  const options = useMemo(() => {
    // A zone the browser does not list, such as UTC in some, is still offered as it is.
    const names = TIME_ZONES.includes(value) ? TIME_ZONES : [value, ...TIME_ZONES];
    return names.map((name) => ({ name, offset: zoneOffset(name) }));
  }, [value]);
  const preview = new Intl.DateTimeFormat("en-GB", { timeZone: value, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(now);

  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <Select id={id} aria-describedby={`${id}-note`} value={value} disabled={disabled} className="w-full" onChange={(event) => onChange(event.target.value)}>
        {options.map(({ name, offset }) => <option key={name} value={name}>{name.replaceAll("_", " ")} ({offset})</option>)}
      </Select>
      <p id={`${id}-note`} className="flex items-start gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
        <Clock aria-hidden className="mt-px size-3.5 shrink-0" />
        <span>
          It is now <time aria-label="Current time in the chosen zone" className="font-medium text-zinc-900 tabular-nums dark:text-zinc-100">{preview}</time> there.
        </span>
      </p>
    </div>
  );
}

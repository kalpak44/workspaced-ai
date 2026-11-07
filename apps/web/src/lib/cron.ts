// Cron fields in order, with the values each allows; day of week counts Sunday as 0 or 7.
const FIELDS = [
  { name: "minute", min: 0, max: 59 },
  { name: "hour", min: 0, max: 23 },
  { name: "day of month", min: 1, max: 31 },
  { name: "month", min: 1, max: 12, names: ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"] },
  { name: "day of week", min: 0, max: 7, names: ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"] },
] as const;

const MACROS = new Set(["@yearly", "@annually", "@monthly", "@weekly", "@daily", "@midnight", "@hourly"]);

function value(text: string, field: (typeof FIELDS)[number]): number | null {
  if (/^\d+$/.test(text)) {
    const number = Number(text);
    return number >= field.min && number <= field.max ? number : null;
  }
  const index = "names" in field ? (field.names as readonly string[]).indexOf(text.toUpperCase()) : -1;
  return index < 0 ? null : index + (field.name === "month" ? 1 : 0);
}

function validPart(part: string, field: (typeof FIELDS)[number]): boolean {
  const [range, step, ...extra] = part.split("/");
  if (extra.length > 0 || (step !== undefined && !/^[1-9]\d*$/.test(step))) return false;
  if (range === "*") return true;
  const [from, to, ...more] = range.split("-");
  if (more.length > 0) return false;
  const start = value(from, field);
  if (start === null) return false;
  if (to === undefined) return true;
  const end = value(to, field);
  return end !== null && start <= end;
}

/** Why a standard five-field cron expression is not valid, or null when it is. */
export function cronError(expression: string): string | null {
  const text = expression.trim();
  if (text === "") return "Enter when the task runs.";
  if (MACROS.has(text.toLowerCase())) return null;
  const parts = text.split(/\s+/);
  if (parts.length !== FIELDS.length) {
    return "Use five fields: minute, hour, day of month, month, day of week.";
  }
  const wrong = FIELDS.find((field, index) => !parts[index].split(",").every((part) => validPart(part, field)));
  return wrong ? `The ${wrong.name} field is not valid.` : null;
}

/** Whether the browser knows the IANA time zone, for example Europe/Sofia. */
export function validTimeZone(zone: string): boolean {
  if (zone.trim() === "") return false;
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

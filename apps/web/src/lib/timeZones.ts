export const BROWSER_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
export const TIME_ZONES: readonly string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];

/** "GMT+02:00" for a zone at this moment, so a list of zones can be read by offset. */
export function zoneOffset(zone: string, at = new Date()): string {
  const part = new Intl.DateTimeFormat("en-US", { timeZone: zone, timeZoneName: "longOffset" })
    .formatToParts(at)
    .find((p) => p.type === "timeZoneName")?.value;
  return part === "GMT" ? "GMT+00:00" : (part ?? "");
}

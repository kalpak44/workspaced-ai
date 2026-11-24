import { formatMoney } from "./format";

// The API spreads a monthly price over the hours of an average month.
export const HOURS_PER_MONTH = 730;

/** What a monthly price in cents comes to over the hours given, in cents, unrounded. */
export function centsFor(monthlyCents: number, hours: number): number {
  return (monthlyCents * hours) / HOURS_PER_MONTH;
}

/** "€0.21 a day": the daily price leads, since a day of a small workspace costs less than a coffee. */
export function perDay(monthlyCents: number): string {
  return `${formatMoney(centsFor(monthlyCents, 24))} a day`;
}

/** "7 days", "36 hours": a period the API states in hours. */
export function formatHours(hours: number): string {
  if (hours % 24 !== 0) return `${hours} ${hours === 1 ? "hour" : "hours"}`;
  const days = hours / 24;
  return `${days} ${days === 1 ? "day" : "days"}`;
}

/** "about 5 days", "about 7 weeks", "about 4 months": how long a balance lasts at a monthly spend. */
export function lasts(balanceCents: number, monthlyCents: number): string {
  const days = balanceCents / centsFor(monthlyCents, 24);
  if (days < 14) {
    const whole = Math.max(1, Math.floor(days));
    return `about ${whole} ${whole === 1 ? "day" : "days"}`;
  }
  if (days < 61) return `about ${Math.floor(days / 7)} weeks`;
  const months = (days * 24) / HOURS_PER_MONTH;
  return `about ${months >= 10 ? Math.floor(months) : Number(months.toFixed(1))} months`;
}

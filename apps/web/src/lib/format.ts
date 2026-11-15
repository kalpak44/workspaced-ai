/** "1M", "200K", "8K": a token count as people say it. */
export function formatTokens(count: number): string {
  if (count >= 1_000_000) {
    return `${Number((count / 1_000_000).toFixed(1))}M`;
  }
  if (count >= 1_000) {
    return `${Math.round(count / 1_000)}K`;
  }
  return String(count);
}

/** "$2 / $10" per million input / output tokens; "free" when both are zero. */
export function formatPrice(input: number | null, output: number | null): string | null {
  if (input === null || output === null) {
    return null;
  }
  if (input === 0 && output === 0) {
    return "free";
  }
  const dollars = (value: number) => `$${Number(value.toFixed(2))}`;
  return `${dollars(input)} / ${dollars(output)}`;
}

const USD = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

/** "$12.40": an amount kept in cents, so sums never pick up floating-point error. */
export function formatMoney(cents: number): string {
  return USD.format(cents / 100);
}

/** "0 B/s", "20 KB/s", "1.2 MB/s": a rate in bytes per second, in the largest whole unit. */
export function formatRate(bytesPerSecond: number): string {
  const units = ["B/s", "KB/s", "MB/s", "GB/s"];
  let value = bytesPerSecond;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${Number(value.toFixed(value < 10 && unit > 0 ? 1 : 0))} ${units[unit]}`;
}

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

/** 1.4 GB, 320 KB: the size of an archive, in the units a file manager would use. */
export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${unit === 0 || value >= 10 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

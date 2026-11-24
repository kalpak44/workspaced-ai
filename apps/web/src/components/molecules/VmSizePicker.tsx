import { RotateCw, Server, TriangleAlert } from "lucide-react";
import type { VmSize } from "../../hooks/useVmSizes";
import { formatMoney } from "../../lib/format";
import { HOURS_PER_MONTH, perDay } from "../../lib/pricing";
import { Button, ChoiceCard, Spinner } from "../atoms";

// Three decimals, since an hour of a small VM costs about a cent.
const HOURLY = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR", minimumFractionDigits: 3, maximumFractionDigits: 3 });

/** What the VM is: cores, memory and disk. */
function detailOf(size: VmSize): string {
  return `${size.cores} vCPU · ${size.memoryGb} GB RAM · ${size.diskGb} GB disk`;
}

/**
 * The price a day first, then a month, then the hour it is charged by; set apart so it is never
 * cut off with the specs.
 */
function PriceTag({ size }: { readonly size: VmSize }) {
  const monthlyCents = size.monthlyGross * 100;
  return (
    <span className="flex shrink-0 flex-col items-end leading-tight">
      <span className="text-sm font-semibold tabular-nums text-zinc-900 dark:text-zinc-100">{perDay(monthlyCents)}</span>
      <span className="text-xs tabular-nums text-zinc-500 dark:text-zinc-400">{formatMoney(monthlyCents)} a month</span>
      <span className="text-xs tabular-nums text-zinc-400 dark:text-zinc-500">{HOURLY.format(size.monthlyGross / HOURS_PER_MONTH)}/hour</span>
    </span>
  );
}

/** The size to start on: the default while it can be had, otherwise none, so a pricier one is never picked silently. */
export function initialVmSize(sizes: readonly VmSize[]): string {
  return sizes.find((size) => size.isDefault && size.available)?.serverType ?? "";
}

interface Props {
  readonly sizes: readonly VmSize[];
  readonly status: "loading" | "ready" | "failed";
  readonly value: string;
  readonly disabled?: boolean;
  /** Set when the size chosen sold out on the way to Hetzner. */
  readonly soldOut?: string | null;
  readonly onChange: (serverType: string) => void;
  readonly onReload: () => void;
}

/**
 * The VM sizes on offer, cheapest first, each with its price. A low-cost size that is unavailable
 * shows but cannot be chosen, and a pricier one only shows while it can be had; while every
 * low-cost one is unavailable, a warning says so and offers to wait.
 */
export function VmSizePicker({ sizes, status, value, disabled = false, soldOut = null, onChange, onReload }: Props) {
  if (status === "loading") {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-zinc-300 px-4 py-3 text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
        <Spinner label="Loading VM types" />
        Loading VM types…
      </div>
    );
  }
  if (status === "failed" || sizes.length === 0) {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-rose-300 px-4 py-3 text-sm text-rose-600 dark:border-rose-900 dark:text-rose-400">
        <p className="flex-1">Could not load VM types.</p>
        <Button label="Retry" variant="secondary" icon={RotateCw} onClick={onReload} />
      </div>
    );
  }
  const cheapSoldOut = sizes.some((size) => size.recommended) && !sizes.some((size) => size.recommended && size.available);
  const shown = sizes.filter((size) => size.recommended || size.available);
  return (
    <>
      {(cheapSoldOut || soldOut) && (
        <div
          role="status"
          aria-label="Sizes unavailable"
          className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 dark:border-amber-950 dark:bg-amber-950/30"
        >
          <div className="flex gap-3">
            <TriangleAlert aria-hidden className="mt-0.5 size-4.5 shrink-0 text-amber-600 dark:text-amber-400" />
            <p className="text-sm leading-6 text-zinc-700 dark:text-zinc-300">
              {soldOut ? `${soldOut} is no longer available. ` : ""}
              {cheapSoldOut
                ? "The low-cost sizes are unavailable right now. They usually come back within a few hours, so you can wait and check again, or pick one of the larger sizes below, which cost more."
                : "Pick another size, or check again in a while."}
            </p>
          </div>
          <div>
            <Button label="Check again" variant="secondary" icon={RotateCw} disabled={disabled} onClick={onReload} />
          </div>
        </div>
      )}
      {shown.map((size) => (
        <ChoiceCard
          key={size.serverType}
          name="workspace-vm-type"
          value={size.serverType}
          label={size.available ? size.serverType : `${size.serverType} · unavailable`}
          detail={detailOf(size)}
          wrapDetail
          aside={<PriceTag size={size} />}
          icon={Server}
          checked={size.serverType === value}
          disabled={disabled || !size.available}
          onChange={onChange}
        />
      ))}
      <p className="text-xs text-zinc-500 dark:text-zinc-400">Prices exclude VAT, which is added when you top up. Billed by the hour from your balance; delete the workspace any time and its charges stop.</p>
    </>
  );
}

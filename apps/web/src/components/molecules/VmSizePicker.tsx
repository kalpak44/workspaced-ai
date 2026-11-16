import { RotateCw, Server, TriangleAlert } from "lucide-react";
import type { VmSize } from "../../hooks/useVmSizes";
import { Button, ChoiceCard, Spinner } from "../atoms";

const PRICE = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" });

/** What the VM is and costs: cores, memory, disk and the monthly price with VAT. */
function detailOf(size: VmSize): string {
  const specs = `${size.cores} vCPU · ${size.memoryGb} GB RAM · ${size.diskGb} GB disk · ${PRICE.format(size.monthlyGross)}/mo`;
  return size.available ? specs : `Sold out · ${specs}`;
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
 * The VM sizes Hetzner sells, cheapest first, each with its price. A sold-out size shows but cannot
 * be chosen; while every low-cost one is sold out, a warning says so and offers to wait.
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
  return (
    <>
      {(cheapSoldOut || soldOut) && (
        <div
          role="status"
          aria-label="Sizes sold out"
          className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 dark:border-amber-950 dark:bg-amber-950/30"
        >
          <div className="flex gap-3">
            <TriangleAlert aria-hidden className="mt-0.5 size-4.5 shrink-0 text-amber-600 dark:text-amber-400" />
            <p className="text-sm leading-6 text-zinc-700 dark:text-zinc-300">
              {soldOut ? `${soldOut} sold out just now. ` : ""}
              {cheapSoldOut
                ? "The low-cost sizes are sold out at Hetzner right now. They usually come back within a few hours, so you can wait and check again, or pick an available size below, which costs more."
                : "Pick another size, or check again in a while."}
            </p>
          </div>
          <div>
            <Button label="Check again" variant="secondary" icon={RotateCw} disabled={disabled} onClick={onReload} />
          </div>
        </div>
      )}
      {sizes.map((size) => (
        <ChoiceCard
          key={size.serverType}
          name="workspace-vm-type"
          value={size.serverType}
          label={size.recommended ? size.serverType : `${size.serverType} (costs more)`}
          detail={detailOf(size)}
          icon={Server}
          checked={size.serverType === value}
          disabled={disabled || !size.available}
          onChange={onChange}
        />
      ))}
    </>
  );
}

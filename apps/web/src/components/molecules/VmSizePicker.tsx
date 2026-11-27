import { ArrowLeftRight, BellRing, Check, RotateCw, TriangleAlert } from "lucide-react";
import { useState } from "react";
import type { VmSize } from "../../hooks/useVmSizes";
import { formatMoney } from "../../lib/format";
import { HOURS_PER_MONTH, perDay } from "../../lib/pricing";
import { Button, Spinner } from "../atoms";

// Three decimals, since an hour of a small VM costs about a cent.
const HOURLY = new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR", minimumFractionDigits: 3, maximumFractionDigits: 3 });

/** "Starter, Builder and Pro". */
function listOf(names: readonly string[]): string {
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)}` : names.join("");
}

interface CardProps {
  readonly size: VmSize;
  readonly checked: boolean;
  readonly disabled: boolean;
  readonly onChange: (serverType: string) => void;
}

/**
 * A plan as a radio drawn like the pricing cards on the site: a row on a phone, a column of three
 * from the small breakpoint up, the featured one badged as recommended. The Hetzner server type
 * stays out of sight but in the title.
 */
function PlanCard({ size, checked, disabled, onChange }: CardProps) {
  const monthlyCents = size.monthlyGross * 100;
  return (
    <label
      title={size.serverType}
      className="grid cursor-pointer grid-cols-[1fr_auto] gap-x-4 gap-y-1 rounded-xl border border-zinc-200 p-4 hover:border-zinc-400 has-checked:border-zinc-900 has-checked:bg-zinc-50 has-checked:ring-1 has-checked:ring-zinc-900 has-focus-visible:ring-2 has-focus-visible:ring-zinc-400 has-disabled:cursor-not-allowed has-disabled:opacity-60 sm:flex sm:flex-col sm:gap-3 dark:border-zinc-700 dark:hover:border-zinc-500 dark:has-checked:border-zinc-200 dark:has-checked:bg-zinc-800/60 dark:has-checked:ring-zinc-200"
    >
      <input
        type="radio"
        name="workspace-vm-type"
        value={size.serverType}
        checked={checked}
        disabled={disabled}
        onChange={() => onChange(size.serverType)}
        className="sr-only"
      />
      <span className="flex flex-wrap items-center gap-2 text-sm font-semibold sm:justify-between">
        {size.plan}{" "}
        {size.available ? size.featured && (
          <span className="rounded-full bg-zinc-900 px-2 py-0.5 text-xs font-medium text-white dark:bg-white dark:text-zinc-900">Recommended</span>
        ) : (
          <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">Sold out</span>
        )}
      </span>
      <span className="col-start-2 row-span-2 row-start-1 flex flex-col items-end leading-tight sm:items-start">
        <span className="text-sm font-semibold tabular-nums sm:text-2xl">{perDay(monthlyCents)}</span>{" "}
        <span className="text-xs tabular-nums text-zinc-500 sm:mt-1 dark:text-zinc-400">{formatMoney(monthlyCents)} a month</span>{" "}
        <span className="text-xs tabular-nums text-zinc-400 dark:text-zinc-500">{HOURLY.format(size.monthlyGross / HOURS_PER_MONTH)}/hour</span>
      </span>
      <ul className="flex flex-wrap gap-x-3 text-xs text-zinc-500 sm:flex-col sm:gap-1.5 sm:text-sm sm:text-zinc-700 dark:text-zinc-400 sm:dark:text-zinc-300">
        {[`${size.cores} vCPU`, `${size.memoryGb} GB RAM`, `${size.diskGb} GB disk`].map((spec) => (
          <li key={spec} className="flex items-center gap-2">
            <Check aria-hidden className="hidden size-4 text-emerald-500 sm:block" />
            {spec}
          </li>
        ))}
      </ul>
    </label>
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
  /** Whether the user already asked to be emailed once a plan is back. */
  readonly waiting?: boolean;
  /** Asks to be emailed once a plan is back; left out, the picker does not offer it. */
  readonly onWait?: () => Promise<void>;
}

/**
 * Start with regular plans, or Flex when those are sold out. Users can explicitly switch tariffs.
 */
export function VmSizePicker({ sizes, status, value, disabled = false, soldOut = null, onChange, onReload, waiting = false, onWait }: Props) {
  const [asking, setAsking] = useState(false);
  const [failed, setFailed] = useState(false);
  const [tariff, setTariff] = useState<"normal" | "flex" | null>(null);
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
  const regular = sizes.filter((size) => size.recommended);
  const standIns = sizes.filter((size) => !size.recommended && size.available);
  // What the email waits for: one of the regular plans, not the pricier stand-in.
  const regularSoldOut = !regular.some((size) => size.available);
  const selected = sizes.find((size) => size.serverType === value);
  const showingFlex = selected ? !selected.recommended : tariff ? tariff === "flex" : regularSoldOut && standIns.length > 0;
  const shown = showingFlex ? standIns : regular;
  const switchTariff = () => {
    const target = showingFlex ? regular : standIns;
    setTariff(showingFlex ? "normal" : "flex");
    onChange(target.find((size) => size.isDefault && size.available)?.serverType
      ?? target.find((size) => size.available)?.serverType ?? "");
  };
  const regularNames = listOf(regular.map((size) => size.plan));
  const ask = async () => {
    setAsking(true);
    setFailed(false);
    try {
      await onWait?.();
    } catch {
      setFailed(true);
    } finally {
      setAsking(false);
    }
  };
  let message = "Pick another plan, or check again in a while.";
  if (regularSoldOut) {
    message = standIns.length > 0
      ? `${regularNames} are sold out right now, usually for a few hours. Until they are back you can take one of these, at a higher price, or wait.`
      : `${regularNames} are sold out right now. They usually come back within a few hours, so check again in a while.`;
  }
  return (
    <>
      {(regularSoldOut || soldOut) && (
        <div
          role="status"
          aria-label="Sizes unavailable"
          className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-3 sm:flex-row sm:items-center dark:border-amber-950 dark:bg-amber-950/30"
        >
          <div className="flex flex-1 gap-3">
            <TriangleAlert aria-hidden className="mt-0.5 size-4.5 shrink-0 text-amber-600 dark:text-amber-400" />
            <div className="flex flex-col gap-1 text-sm leading-6 text-zinc-700 dark:text-zinc-300">
              <p>{soldOut ? `${soldOut} is no longer available. ` : ""}{message}</p>
              {onWait && regularSoldOut && waiting && <p>We'll email you when a plan is back.</p>}
              {failed && <p role="alert" className="text-rose-600 dark:text-rose-400">Could not save that. Please try again.</p>}
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2 pl-7.5 sm:pl-0">
            <Button label="Check again" variant="secondary" icon={RotateCw} disabled={disabled} onClick={onReload} />
            {onWait && regularSoldOut && !waiting && (
              <Button label="Email me when a plan is back" variant="secondary" icon={BellRing} busy={asking} disabled={disabled} onClick={() => void ask()} />
            )}
          </div>
        </div>
      )}
      {regular.length > 0 && standIns.length > 0 && (
        <div className="flex items-center justify-between gap-3 text-xs">
          <span className="font-medium text-zinc-700 dark:text-zinc-300">{showingFlex ? "Flex tariffs · higher price" : "Normal tariffs"}</span>
          <button type="button" disabled={disabled} onClick={switchTariff}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1.5 font-medium text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-400 disabled:opacity-50 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100">
            <ArrowLeftRight aria-hidden className="size-3.5" />
            {showingFlex ? "Show normal tariffs" : "Show Flex tariffs"}
          </button>
        </div>
      )}
      <div className="grid gap-2 sm:grid-cols-3 sm:gap-3">
        {shown.map((size) => (
          <PlanCard
            key={size.serverType}
            size={size}
            checked={size.serverType === value}
            disabled={disabled || !size.available}
            onChange={onChange}
          />
        ))}
      </div>
      <p className="text-xs text-zinc-500 dark:text-zinc-400">Prices exclude VAT, which is added when you top up. Billed by the hour from your balance; delete the workspace any time and its charges stop.</p>
    </>
  );
}

import { CreditCard } from "lucide-react";
import { useState } from "react";
import { useVmSizes } from "../../hooks/useVmSizes";
import { formatMoney } from "../../lib/format";
import { lasts, perDay } from "../../lib/pricing";
import { Button, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

const PRESETS = [2500, 5000, 10000, 25000];
// Sized to a first workspace rather than a fleet; €5 is left out, as Stripe's fixed fee takes a
// large share of it.
const FIRST_PRESETS = [1000, 2500, 5000];
const MIN_CENTS = 500;
const MAX_CENTS = 100000;

// Whole euros without decimals, as someone would type them.
const euros = (cents: number) => (cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2));

/** "€10 lasts about 4 weeks of a Starter workspace at €0.35 a day": what a first top-up buys, on the default plan. */
function FirstTopUpHint({ amountCents }: { readonly amountCents: number }) {
  const { sizes } = useVmSizes();
  const plan = sizes.find((size) => size.isDefault) ?? sizes[0];
  if (!plan) return null;
  const monthlyCents = plan.monthlyGross * 100;
  return (
    <p role="status" aria-label="What it buys" className="text-sm text-zinc-500 dark:text-zinc-400">
      {formatMoney(amountCents)} lasts {lasts(amountCents, monthlyCents)} of a {plan.plan} workspace at {perDay(monthlyCents)}.
    </p>
  );
}

interface Props {
  readonly balanceCents: number;
  /** Charged on top; the balance gets the amount before it. Absent from an API older than the app. */
  readonly vatPercent: number | undefined;
  /** Nothing was paid in yet, so smaller amounts are offered, with what they buy. */
  readonly first?: boolean;
  /** Set while the payment page is being opened, so a second click cannot start another. */
  readonly busy?: boolean;
  readonly onConfirm: (amountCents: number) => void;
  readonly onClose: () => void;
}

/** Picks how much to add to the balance before handing over to payment. */
export function AddFundsDialog({ balanceCents, vatPercent, first = false, busy = false, onConfirm, onClose }: Props) {
  const presets = first ? FIRST_PRESETS : PRESETS;
  // The field holds the amount; a preset only fills it in, and shows as chosen while it matches.
  const [custom, setCustom] = useState(euros(presets[0]));
  const amount = custom.trim() === "" ? Number.NaN : Math.round(Number(custom) * 100);
  const valid = Number.isFinite(amount) && amount >= MIN_CENTS && amount <= MAX_CENTS;
  // Without the rate the total is unknown, and a charge nobody was shown is not taken.
  const rate = typeof vatPercent === "number" && Number.isFinite(vatPercent) ? vatPercent : null;
  // Rounded half up, as Stripe rounds the tax it charges.
  const vat = valid && rate !== null ? Math.round((amount * rate) / 100) : 0;
  const total = valid ? amount + vat : 0;
  const payable = valid && rate !== null;
  const error = custom.trim() !== "" && !valid
    ? `Enter between ${formatMoney(MIN_CENTS)} and ${formatMoney(MAX_CENTS)}.`
    : null;

  return (
    <Modal title="Add funds" description={`Your balance is ${formatMoney(balanceCents)}.`} onClose={onClose}>
      <form onSubmit={(event) => { event.preventDefault(); if (payable && !busy) onConfirm(amount); }} className="flex min-h-0 flex-col">
        <ModalBody>
          <div role="radiogroup" aria-label="Amount" className={`grid gap-2 ${presets.length === 3 ? "grid-cols-3" : "grid-cols-2 sm:grid-cols-4"}`}>
            {presets.map((cents) => (
              <button key={cents} type="button" role="radio" aria-checked={amount === cents}
                onClick={() => setCustom(euros(cents))}
                className={`rounded-lg border px-3 py-2.5 text-sm font-medium ${amount === cents ? "border-zinc-900 bg-zinc-50 dark:border-zinc-200 dark:bg-zinc-800/60" : "border-zinc-200 hover:border-zinc-400 dark:border-zinc-700 dark:hover:border-zinc-500"}`}>
                {formatMoney(cents)}
              </button>
            ))}
          </div>
          {first && valid && <FirstTopUpHint amountCents={amount} />}
          <TextField id="custom-amount" label="Or another amount (EUR)" type="number" inputMode="decimal"
            min={MIN_CENTS / 100} max={MAX_CENTS / 100} step="0.01" placeholder="e.g. 15"
            value={custom} error={error} onChange={(event) => setCustom(event.target.value)} />
          <dl aria-label="Summary" className="space-y-1.5 border-t border-zinc-200 pt-4 text-sm tabular-nums dark:border-zinc-800">
            <div className="flex justify-between">
              <dt className="text-zinc-500 dark:text-zinc-400">Amount</dt>
              <dd>{formatMoney(valid ? amount : 0)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-zinc-500 dark:text-zinc-400">{rate === null ? "VAT" : `VAT (${rate}%)`}</dt>
              <dd>{rate === null ? "—" : formatMoney(vat)}</dd>
            </div>
            <div className="flex justify-between font-medium">
              <dt>Total charged</dt>
              <dd>{rate === null ? "—" : formatMoney(total)}</dd>
            </div>
            <div className="flex justify-between pt-1.5">
              <dt className="text-zinc-500 dark:text-zinc-400">Balance after</dt>
              <dd className="font-medium">{formatMoney(balanceCents + (valid ? amount : 0))}</dd>
            </div>
          </dl>
          {rate === null && (
            <p role="alert" className="text-sm text-red-600 dark:text-red-400">
              VAT cannot be worked out right now, so payment is unavailable. Please try again later.
            </p>
          )}
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={payable ? `Pay ${formatMoney(total)}` : "Pay"} icon={CreditCard} type="submit" disabled={!payable} busy={busy} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

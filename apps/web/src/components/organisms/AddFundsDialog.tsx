import { CreditCard } from "lucide-react";
import { useState } from "react";
import { formatMoney } from "../../lib/format";
import { Button, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

const PRESETS = [1000, 2500, 5000, 10000];
const MIN_CENTS = 500;
const MAX_CENTS = 100000;

interface Props {
  readonly balanceCents: number;
  readonly onConfirm: (amountCents: number) => void;
  readonly onClose: () => void;
}

/** Picks how much to add to the balance before handing over to payment. */
export function AddFundsDialog({ balanceCents, onConfirm, onClose }: Props) {
  const [preset, setPreset] = useState<number | null>(2500);
  const [custom, setCustom] = useState("");
  const amount = preset ?? Math.round(Number(custom) * 100);
  const valid = Number.isFinite(amount) && amount >= MIN_CENTS && amount <= MAX_CENTS;
  const error = preset === null && custom !== "" && !valid
    ? `Enter between ${formatMoney(MIN_CENTS)} and ${formatMoney(MAX_CENTS)}.`
    : null;

  return (
    <Modal title="Add funds" description={`Your balance is ${formatMoney(balanceCents)}.`} onClose={onClose}>
      <form onSubmit={(event) => { event.preventDefault(); if (valid) onConfirm(amount); }} className="flex min-h-0 flex-col">
        <ModalBody>
          <div role="radiogroup" aria-label="Amount" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {PRESETS.map((cents) => (
              <button key={cents} type="button" role="radio" aria-checked={preset === cents}
                onClick={() => { setPreset(cents); setCustom(""); }}
                className={`rounded-lg border px-3 py-2.5 text-sm font-medium ${preset === cents ? "border-zinc-900 bg-zinc-50 dark:border-zinc-200 dark:bg-zinc-800/60" : "border-zinc-200 hover:border-zinc-400 dark:border-zinc-700 dark:hover:border-zinc-500"}`}>
                {formatMoney(cents)}
              </button>
            ))}
          </div>
          <TextField id="custom-amount" label="Or another amount (USD)" type="number" inputMode="decimal"
            min={MIN_CENTS / 100} max={MAX_CENTS / 100} step="0.01" placeholder="e.g. 15"
            value={custom} error={error} onChange={(event) => { setCustom(event.target.value); setPreset(null); }} />
          <div className="flex justify-between border-t border-zinc-200 pt-4 text-sm dark:border-zinc-800">
            <span className="text-zinc-500 dark:text-zinc-400">Balance after</span>
            <span className="font-medium">{formatMoney(balanceCents + (valid ? amount : 0))}</span>
          </div>
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={valid ? `Pay ${formatMoney(amount)}` : "Pay"} icon={CreditCard} type="submit" disabled={!valid} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

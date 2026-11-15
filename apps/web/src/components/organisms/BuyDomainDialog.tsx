import { Globe, Plus } from "lucide-react";
import { formatMoney } from "../../lib/format";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

interface Props {
  readonly name: string;
  readonly priceCents: number;
  /** Null while the balance is still loading. */
  readonly balanceCents: number | null;
  readonly onConfirm: () => void;
  readonly onAddFunds: () => void;
  readonly onClose: () => void;
}

/** Confirms a one-year registration, paid from the balance. */
export function BuyDomainDialog({ name, priceCents, balanceCents, onConfirm, onAddFunds, onClose }: Props) {
  const short = balanceCents !== null && balanceCents < priceCents;
  return (
    <Modal title={`Buy ${name}?`} description="Registered for one year and paid from your balance." onClose={onClose}>
      <ModalBody>
        <div className="flex items-center gap-3 rounded-xl border border-zinc-200 p-4 dark:border-zinc-800">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200">
            <Globe aria-hidden className="size-4" />
          </span>
          <span className="min-w-0 flex-1 truncate font-mono text-sm">{name}</span>
          <span className="text-sm font-medium tabular-nums">{formatMoney(priceCents)} / year</span>
        </div>
        <dl className="space-y-2 text-sm">
          <div className="flex justify-between">
            <dt className="text-zinc-500 dark:text-zinc-400">Balance</dt>
            <dd className="tabular-nums">{balanceCents === null ? "…" : formatMoney(balanceCents)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-zinc-500 dark:text-zinc-400">Balance after</dt>
            <dd className="font-medium tabular-nums">{balanceCents === null ? "…" : formatMoney(balanceCents - priceCents)}</dd>
          </div>
        </dl>
        {short && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">Your balance does not cover this domain. Add funds first.</p>}
      </ModalBody>
      <ModalFooter>
        <Button label="Cancel" variant="secondary" onClick={onClose} />
        {short
          ? <Button label="Add funds" icon={Plus} onClick={onAddFunds} />
          : <Button label={`Buy for ${formatMoney(priceCents)}`} onClick={onConfirm} disabled={balanceCents === null} autoFocus />}
      </ModalFooter>
    </Modal>
  );
}

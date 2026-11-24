import { Check, CreditCard } from "lucide-react";
import { useState } from "react";
import { useBilling } from "../../hooks/useBilling";
import { keepAfterTopUp, useKeepWorkspace } from "../../hooks/useKeepWorkspace";
import { useVmSizes } from "../../hooks/useVmSizes";
import type { Workspace } from "../../hooks/useWorkspaces";
import { formatMoney } from "../../lib/format";
import { perDay } from "../../lib/pricing";
import { Button } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

// The smallest top-up the API takes.
const MIN_TOP_UP_CENTS = 500;

interface Props {
  readonly workspace: Workspace;
  readonly onKept: (workspace: Workspace) => void;
  readonly onClose: () => void;
}

/**
 * Keeps the free demo as a workspace of the user's own, on the same machine with nothing lost,
 * adding to the balance first when it does not cover the first week.
 */
export function KeepWorkspaceDialog({ workspace, onKept, onClose }: Props) {
  const { busy, result, keep } = useKeepWorkspace(workspace.id);
  const { billing, pay } = useBilling();
  const { sizes } = useVmSizes();
  const [paying, setPaying] = useState(false);
  const [payFailed, setPayFailed] = useState(false);
  const plan = sizes.find((size) => size.serverType === workspace.serverType);

  const submit = async () => {
    const outcome = await keep();
    if (outcome.kind === "kept") onKept(outcome.workspace);
  };

  // Whole euros read better than a cent-exact shortfall, and the API takes no less than €5.
  const addCents = result?.kind === "short" ? Math.max(MIN_TOP_UP_CENTS, Math.ceil(result.shortCents / 100) * 100) : 0;
  const vatCents = billing ? Math.round((addCents * billing.vatPercent) / 100) : null;
  const topUp = async () => {
    setPaying(true);
    setPayFailed(false);
    keepAfterTopUp(workspace.id);
    if ((await pay(addCents)) !== "redirected") {
      setPaying(false);
      setPayFailed(true);
    }
  };

  let body;
  let action;
  if (result?.kind === "short") {
    body = <>
      <p>Your balance needs <strong>{formatMoney(result.shortCents)}</strong> more to cover its first week.</p>
      <p className="text-sm text-zinc-500 dark:text-zinc-400">
        Add {formatMoney(addCents)}{vatCents === null ? "" : ` plus ${formatMoney(vatCents)} VAT`} with Stripe, and {workspace.name} is kept as soon as you are back.
      </p>
      {payFailed && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">The payment page could not be opened. Please try again.</p>}
    </>;
    action = <Button label={`Add ${formatMoney(addCents)} and keep it`} icon={CreditCard} busy={paying} disabled={vatCents === null} onClick={() => void topUp()} />;
  } else {
    body = <>
      <p>{workspace.name} stays exactly as it is: your page, your files and your conversations, on the same machine.</p>
      <p className="text-sm text-zinc-500 dark:text-zinc-400">
        It is no longer deleted, and from now on it is paid from your balance{plan ? `: ${plan.plan ?? plan.serverType} at ${perDay(plan.monthlyGross * 100)}` : ""}. You can delete it whenever you like.
      </p>
      {result?.kind === "refused" && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{result.reason}</p>}
    </>;
    action = <Button label="Keep it" icon={Check} busy={busy} onClick={() => void submit()} />;
  }

  return (
    <Modal title={`Keep ${workspace.name}`} onClose={onClose}>
      <ModalBody>{body}</ModalBody>
      <ModalFooter>
        <Button label="Not now" variant="secondary" onClick={onClose} disabled={busy || paying} />
        {action}
      </ModalFooter>
    </Modal>
  );
}

import { CreditCard, Download, Plus, Receipt, TriangleAlert, Wallet } from "lucide-react";
import { type SubmitEvent, useEffect, useState } from "react";
import { usePagination } from "../../hooks/usePagination";
import { type AutoTopUp, type Billing, type TopUp, useBilling } from "../../hooks/useBilling";
import { formatMoney } from "../../lib/format";
import { Button, IconButton, TextField } from "../atoms";
import { EmptyState, PageHeader, Pagination, SettingsSection, Toast } from "../molecules";
import { AddFundsDialog } from "../organisms";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium" });
const DAY = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeZone: "UTC" });
const FUNDED_UNTIL = new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric" });
const DELETES_AT = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
const PAGE_SIZE = 10;
const DAY_MS = 24 * 60 * 60 * 1000;
// The funds reminders start a week out, so the card warns from then too.
const WARN_DAYS = 7;

const STATUS: Record<TopUp["status"], { readonly label: string; readonly className: string }> = {
  PAID: { label: "Paid", className: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-400" },
  PENDING: { label: "Pending", className: "bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-400" },
  FAILED: { label: "Failed", className: "bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-400" },
  EXPIRED: { label: "Expired", className: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400" },
};

/** "Lasts until Fri, Dec 12 · about 25 days": what the balance still pays for at the current spend. */
function Runway({ billing }: { readonly billing: Billing }) {
  if (!billing.fundedUntil) {
    const paused = billing.workspaces.some((workspace) => workspace.deletesAt);
    return paused
      ? <p className="text-sm font-medium text-amber-300">Your workspaces are paused until you add funds.</p>
      : <p className="text-sm text-white/70">No workspace is drawing on it.</p>;
  }
  const until = new Date(billing.fundedUntil);
  const days = Math.floor((until.getTime() - Date.now()) / DAY_MS);
  const span = days < 1 ? "less than a day" : `about ${days} ${days === 1 ? "day" : "days"}`;
  return (
    <p className={`text-sm ${days < WARN_DAYS ? "font-medium text-amber-300" : "text-white/70"}`}>
      Lasts until {FUNDED_UNTIL.format(until)} · {span} at {formatMoney(billing.monthlySpendCents)} a month
    </p>
  );
}

/** "3 days", "12 hours": how long a paused workspace is kept. */
function keptFor(hours: number): string {
  if (hours < 24) return `${hours} ${hours === 1 ? "hour" : "hours"}`;
  const days = Math.round(hours / 24);
  return `${days} ${days === 1 ? "day" : "days"}`;
}

/**
 * What running out costs, said before it happens: once the balance lasts under a week, or while
 * workspaces are already paused. Null otherwise.
 */
function FundsWarning({ billing, onAddFunds }: { readonly billing: Billing; readonly onAddFunds: () => void }) {
  const fee = formatMoney(billing.reactivationFeeCents);
  const kept = keptFor(billing.pausedForHours);
  const paused = billing.workspaces.filter((workspace) => workspace.deletesAt).length;
  const until = billing.fundedUntil ? new Date(billing.fundedUntil) : null;
  let message;
  if (paused > 0) {
    message = `${paused === 1 ? "A workspace is" : `${paused} workspaces are`} paused because the balance ran out. Add funds to start ${paused === 1 ? "it" : "them"} again: each costs a ${fee} reactivation fee on top of its hours. A workspace still paused ${kept} after it stopped is deleted with its files, sessions and secrets.`;
  } else if (until && until.getTime() - Date.now() < WARN_DAYS * DAY_MS) {
    message = `Your balance runs out on ${FUNDED_UNTIL.format(until)}. Your workspaces are then paused, and starting each again costs a ${fee} reactivation fee. Any still paused ${kept} later is deleted with its files, sessions and secrets. Add funds before then to avoid both.`;
  } else {
    return null;
  }
  return (
    <section role="alert" aria-label="Funds warning" className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-6 text-zinc-700 sm:flex-row sm:items-start dark:border-amber-950 dark:bg-amber-950/30 dark:text-zinc-300">
      <TriangleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
      <p className="flex-1">{message}</p>
      <Button label="Add funds" icon={Plus} variant="secondary" onClick={onAddFunds} />
    </section>
  );
}

// The API takes €5.00 to €1,000.00 for either amount, as it does for a top-up.
const MIN_EUROS = 5;
const MAX_EUROS = 1000;

/**
 * The saved card, and whether it is charged when the balance runs low. The amounts are edited as
 * euros and saved as cents; turning it on needs a card, which Stripe's own page saves.
 */
function AutoTopUpSection({ billing, onSaveCard, onRemoveCard, onSave }: {
  readonly billing: Billing;
  readonly onSaveCard: () => void;
  readonly onRemoveCard: () => Promise<void>;
  readonly onSave: (settings: AutoTopUp) => Promise<void>;
}) {
  const saved = billing.autoTopUp;
  const [enabled, setEnabled] = useState(saved.enabled);
  const [below, setBelow] = useState(String(saved.belowCents / 100));
  const [amount, setAmount] = useState(String(saved.amountCents / 100));
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  // A saved change, or the card's removal, comes back in the billing; the form follows it.
  useEffect(() => {
    setEnabled(saved.enabled);
    setBelow(String(saved.belowCents / 100));
    setAmount(String(saved.amountCents / 100));
  }, [saved.enabled, saved.belowCents, saved.amountCents]);

  const euros = (value: string) => Number(value);
  const valid = (value: string) => value.trim() !== "" && euros(value) >= MIN_EUROS && euros(value) <= MAX_EUROS;
  const range = `Between ${formatMoney(MIN_EUROS * 100)} and ${formatMoney(MAX_EUROS * 100)}.`;
  const changed = enabled !== saved.enabled || Math.round(euros(below) * 100) !== saved.belowCents || Math.round(euros(amount) * 100) !== saved.amountCents;

  const submit = (event: SubmitEvent) => {
    event.preventDefault();
    setBusy(true);
    setFailed(false);
    onSave({ enabled, belowCents: Math.round(euros(below) * 100), amountCents: Math.round(euros(amount) * 100) })
      .catch(() => setFailed(true))
      .finally(() => setBusy(false));
  };

  return (
    <SettingsSection title="Automatic top-up" description="Charge a saved card whenever the balance runs low, so your workspaces are never paused. VAT is charged on top, and Stripe issues an invoice for each top-up.">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-zinc-200 px-4 py-3 text-sm dark:border-zinc-800">
        <span className="flex items-center gap-2">
          <CreditCard aria-hidden className="size-4 text-zinc-500" />
          {billing.card ?? <span className="text-zinc-500 dark:text-zinc-400">No card saved</span>}
        </span>
        <span className="flex gap-2">
          {billing.card && <Button label="Remove card" variant="secondary" disabled={busy} onClick={() => { setBusy(true); void onRemoveCard().finally(() => setBusy(false)); }} />}
          <Button label={billing.card ? "Replace card" : "Save a card"} variant="secondary" disabled={busy} onClick={onSaveCard} />
        </span>
      </div>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <label className="flex items-center gap-2 text-sm font-medium">
          <input type="checkbox" className="size-4 accent-zinc-900 dark:accent-zinc-100" checked={enabled && billing.card !== null} disabled={!billing.card || busy}
            onChange={(e) => setEnabled(e.currentTarget.checked)} />
          Top up automatically
        </label>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField id="auto-top-up-below" label="When the balance drops below (EUR)" type="number" inputMode="decimal" min={MIN_EUROS} max={MAX_EUROS} step="0.01"
            value={below} disabled={busy} error={valid(below) ? null : range} onChange={(e) => setBelow(e.currentTarget.value)} />
          <TextField id="auto-top-up-amount" label="Add (EUR)" type="number" inputMode="decimal" min={MIN_EUROS} max={MAX_EUROS} step="0.01"
            value={amount} disabled={busy} error={valid(amount) ? null : range}
            hint={valid(amount) && Number.isFinite(billing.vatPercent) ? `Charged as ${formatMoney(Math.round(euros(amount) * 100 * (1 + billing.vatPercent / 100)))} with VAT.` : undefined}
            onChange={(e) => setAmount(e.currentTarget.value)} />
        </div>
        {failed && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">The setting could not be saved. Please try again.</p>}
        <div><Button label="Save" type="submit" busy={busy} disabled={!changed || !valid(below) || !valid(amount)} /></div>
      </form>
    </SettingsSection>
  );
}

/** Stripe sends the browser back with ?top_up=<id>&outcome=success|cancelled. */
function returnedTopUp(): { readonly id: string; readonly cancelled: boolean } | null {
  const query = new URLSearchParams(globalThis.location.search);
  const id = query.get("top_up");
  return id ? { id, cancelled: query.get("outcome") === "cancelled" } : null;
}

/** The account's balance, a way to top it up, and every top-up with its invoice. */
export function BillingPage() {
  const { billing, status, pay: startPayment, confirm, openInvoice, saveCard, confirmCard, removeCard, setAutoTopUp } = useBilling();
  const [adding, setAdding] = useState(false);
  const [paying, setPaying] = useState(false);
  const [notice, setNotice] = useState("");
  const topUps = usePagination(billing?.topUps ?? [], PAGE_SIZE);
  const days = usePagination(billing?.usage ?? [], PAGE_SIZE);

  useEffect(() => {
    const query = new URLSearchParams(globalThis.location.search);
    const cardSession = query.get("card_session");
    if (!cardSession && query.get("card") !== "cancelled") return;
    globalThis.history.replaceState(globalThis.history.state, "", globalThis.location.pathname);
    if (!cardSession) {
      setNotice("Saving the card was cancelled.");
      return;
    }
    confirmCard(cardSession).then(
      () => setNotice("Your card was saved. Turn on automatic top-ups to use it."),
      () => setNotice("Your card is being saved. It shows here once Stripe confirms it."),
    );
  }, [confirmCard]);

  const startCardSetup = () => {
    setNotice("");
    void saveCard().then((outcome) => {
      if (outcome === "redirected") return;
      setNotice(outcome === "unavailable"
        ? "Payments are unavailable right now. Please try again later."
        : "The card could not be saved. Please try again.");
    });
  };

  useEffect(() => {
    const returned = returnedTopUp();
    if (!returned) return;
    // Dropped at once, so a reload of the page does not report the payment twice.
    globalThis.history.replaceState(globalThis.history.state, "", globalThis.location.pathname);
    if (returned.cancelled) {
      setNotice("Payment cancelled. Nothing was charged.");
      return;
    }
    // The webhook may not have arrived yet, so the API is asked to read the payment back itself.
    confirm(returned.id).then(
      (topUp) => setNotice(topUp.status === "PAID"
        ? `${formatMoney(topUp.amountCents)} was added to your balance.`
        : "Your payment is being confirmed. The balance updates once it clears."),
      () => setNotice("Your payment could not be confirmed yet. Check back in a minute."),
    );
  }, [confirm]);

  const pay = (cents: number) => {
    setPaying(true);
    setNotice("");
    void startPayment(cents).then((outcome) => {
      if (outcome === "redirected") return;
      setPaying(false);
      setAdding(false);
      setNotice(outcome === "unavailable"
        ? "Payments are unavailable right now. Please try again later."
        : "The payment could not be started. Please try again.");
    });
  };

  if (status !== "ready" || !billing) {
    return (
      <>
        <PageHeader title="Billing" />
        {status === "failed"
          ? <EmptyState icon={Wallet} title="Billing is unavailable" hint="The balance could not be loaded. Please try again later." />
          : <EmptyState busy title="Loading billing…" hint="Fetching your balance and top-ups" />}
      </>
    );
  }

  return (
    <>
      <PageHeader title="Billing" description="Your balance pays for workspaces by the hour. Top it up any time." />
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-4 pt-6 pb-10 sm:px-6">
        <section aria-label="Balance" className="relative flex flex-col gap-4 overflow-hidden rounded-2xl bg-zinc-900 p-5 text-white shadow-lg sm:flex-row sm:items-center sm:justify-between sm:p-6 dark:bg-zinc-900 dark:ring-1 dark:ring-white/10 [&_button]:bg-white [&_button]:text-zinc-900 [&_button:hover]:bg-zinc-200 dark:[&_button]:bg-white dark:[&_button]:text-zinc-900 dark:[&_button:hover]:bg-zinc-200">
          <span aria-hidden className="pointer-events-none absolute -top-16 -right-10 size-48 rounded-full bg-linear-to-br from-sky-400/30 via-fuchsia-500/20 to-orange-400/20 blur-2xl" />
          <div className="relative flex items-center gap-4">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-white/10">
              <Wallet aria-hidden className="size-5" />
            </span>
            <div>
              <p className="text-sm text-white/70">Current balance</p>
              <p className="text-3xl font-semibold tracking-tight tabular-nums">{formatMoney(billing.balanceCents)}</p>
              <Runway billing={billing} />
            </div>
          </div>
          <div className="relative"><Button label="Add funds" icon={Plus} onClick={() => { setAdding(true); setNotice(""); }} /></div>
        </section>

        <FundsWarning billing={billing} onAddFunds={() => { setAdding(true); setNotice(""); }} />

        <SettingsSection title="Spending" description={`Each workspace is charged by the hour from your balance while it exists. Deleting one stops its charges. When the balance runs out, workspaces are paused rather than deleted: add funds within ${keptFor(billing.pausedForHours)} and they start again, for a ${formatMoney(billing.reactivationFeeCents)} reactivation fee each.`}>
          {billing.workspaces.length === 0 && billing.usage.length === 0 ? (
            <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-zinc-300 py-10 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
              <Receipt aria-hidden className="size-6" />
              Nothing is charged yet. Workspaces you create are charged here.
            </div>
          ) : (
            <>
              {billing.workspaces.length > 0 && (
                <div className="-mx-4 overflow-x-auto sm:-mx-6">
                  <table aria-label="Workspaces" className="w-full text-left text-sm">
                    <thead className="border-y border-zinc-200 bg-zinc-50 text-xs text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-400">
                      <tr>
                        <th scope="col" className="px-4 py-2 font-medium sm:pl-6">Workspace</th>
                        <th scope="col" className="px-4 py-2 text-right font-medium sm:pr-6">Per month</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                      {billing.workspaces.map((workspace) => (
                        <tr key={workspace.id}>
                          <td className="px-4 py-3 [overflow-wrap:anywhere] sm:pl-6">
                            {workspace.name}
                            {workspace.deletesAt && (
                              <span className="mt-0.5 block text-xs font-medium text-amber-700 dark:text-amber-400">
                                Paused · deleted {DELETES_AT.format(new Date(workspace.deletesAt))} unless you add funds
                              </span>
                            )}
                          </td>
                          <td className={`whitespace-nowrap px-4 py-3 text-right tabular-nums sm:pr-6 ${workspace.deletesAt ? "text-zinc-400 line-through dark:text-zinc-500" : ""}`}>{formatMoney(workspace.monthlyCents)}</td>
                        </tr>
                      ))}
                    </tbody>
                    {billing.workspaces.filter((workspace) => !workspace.deletesAt).length > 1 && (
                      <tfoot className="border-t border-zinc-200 font-medium dark:border-zinc-800">
                        <tr>
                          <th scope="row" className="px-4 py-3 text-left font-medium sm:pl-6">Total</th>
                          <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums sm:pr-6">{formatMoney(billing.monthlySpendCents)}</td>
                        </tr>
                      </tfoot>
                    )}
                  </table>
                </div>
              )}
              {billing.usage.length > 0 && (
                <>
                  <h3 className="text-sm font-medium">Charged in the last 30 days</h3>
                  <div className="-mx-4 overflow-x-auto sm:-mx-6">
                    <table aria-label="Daily charges" className="w-full text-left text-sm">
                      <thead className="border-y border-zinc-200 bg-zinc-50 text-xs text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-400">
                        <tr>
                          <th scope="col" className="px-4 py-2 font-medium sm:pl-6">Day (UTC)</th>
                          <th scope="col" className="px-4 py-2 text-right font-medium sm:pr-6">Charged</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                        {days.items.map((day) => (
                          <tr key={day.date}>
                            <td className="whitespace-nowrap px-4 py-3 sm:pl-6">{DAY.format(new Date(day.date))}</td>
                            <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums sm:pr-6">{formatMoney(day.amountCents)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <Pagination label="Charge pages" noun="day" page={days.page} pageCount={days.pageCount} onChange={days.setPage}
                    range={{ first: days.first, last: days.last, total: days.total }} />
                </>
              )}
            </>
          )}
        </SettingsSection>

        <AutoTopUpSection billing={billing} onSaveCard={startCardSetup} onRemoveCard={removeCard} onSave={setAutoTopUp} />

        <SettingsSection title="Top-ups" description="Every payment into your balance, with Stripe's invoice for it.">
          {billing.topUps.length === 0 ? (
            <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-zinc-300 py-10 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
              <Receipt aria-hidden className="size-6" />
              No top-ups yet. Each payment adds one here.
            </div>
          ) : (
            <>
            <div className="-mx-4 overflow-x-auto sm:-mx-6">
              <table className="w-full text-left text-sm">
                <thead className="border-y border-zinc-200 bg-zinc-50 text-xs text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-400">
                  <tr>
                    <th scope="col" className="px-4 py-2 font-medium sm:pl-6">Date</th>
                    <th scope="col" className="hidden px-4 py-2 font-medium sm:table-cell">Invoice</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Amount</th>
                    <th scope="col" className="px-4 py-2 font-medium">Status</th>
                    <th scope="col" className="px-4 py-2 sm:pr-6"><span className="sr-only">Download</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  {topUps.items.map((topUp) => (
                    <tr key={topUp.id} className="transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800/40">
                      <td className="whitespace-nowrap px-4 py-3 sm:pl-6">
                        {DATE.format(new Date(topUp.paidAt ?? topUp.createdAt))}
                        {topUp.automatic && <span className="ml-2 rounded-full bg-zinc-100 px-1.5 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">Auto</span>}
                        {topUp.invoiceNumber && <span className="block font-mono text-xs text-zinc-500 sm:hidden dark:text-zinc-400">{topUp.invoiceNumber}</span>}
                      </td>
                      <td className="hidden whitespace-nowrap px-4 py-3 font-mono text-xs sm:table-cell">{topUp.invoiceNumber ?? "—"}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums">{formatMoney(topUp.amountCents)}</td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[topUp.status].className}`}>{STATUS[topUp.status].label}</span>
                      </td>
                      <td className="px-4 py-3 text-right sm:pr-6">
                        {/* Stripe issues an invoice only once the payment clears. */}
                        <IconButton label={`Download invoice for ${formatMoney(topUp.amountCents)} on ${DATE.format(new Date(topUp.createdAt))}`}
                          icon={Download} disabled={!topUp.invoiceAvailable} onClick={() => openInvoice(topUp.id)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination label="Top-up pages" noun="top-up" page={topUps.page} pageCount={topUps.pageCount} onChange={topUps.setPage}
              range={{ first: topUps.first, last: topUps.last, total: topUps.total }} />
            </>
          )}
        </SettingsSection>
        <Toast message={notice} />
      </div>
      {adding && (
        <AddFundsDialog
          balanceCents={billing.balanceCents}
          vatPercent={billing.vatPercent}
          busy={paying}
          onClose={() => setAdding(false)}
          onConfirm={pay}
        />
      )}
    </>
  );
}

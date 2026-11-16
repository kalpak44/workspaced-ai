import { Download, Plus, Receipt, Wallet } from "lucide-react";
import { useEffect, useState } from "react";
import { usePagination } from "../../hooks/usePagination";
import { type TopUp, useBilling } from "../../hooks/useBilling";
import { formatMoney } from "../../lib/format";
import { Button, IconButton } from "../atoms";
import { EmptyState, PageHeader, Pagination, SettingsSection, Toast } from "../molecules";
import { AddFundsDialog } from "../organisms";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium" });
const PAGE_SIZE = 10;

const STATUS: Record<TopUp["status"], { readonly label: string; readonly className: string }> = {
  PAID: { label: "Paid", className: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-400" },
  PENDING: { label: "Pending", className: "bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-400" },
  FAILED: { label: "Failed", className: "bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-400" },
  EXPIRED: { label: "Expired", className: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400" },
};

/** Stripe sends the browser back with ?top_up=<id>&outcome=success|cancelled. */
function returnedTopUp(): { readonly id: string; readonly cancelled: boolean } | null {
  const query = new URLSearchParams(globalThis.location.search);
  const id = query.get("top_up");
  return id ? { id, cancelled: query.get("outcome") === "cancelled" } : null;
}

/** The account's balance, a way to top it up, and every top-up with its invoice. */
export function BillingPage() {
  const { billing, status, pay: startPayment, confirm, openInvoice } = useBilling();
  const [adding, setAdding] = useState(false);
  const [paying, setPaying] = useState(false);
  const [notice, setNotice] = useState("");
  const topUps = usePagination(billing?.topUps ?? [], PAGE_SIZE);

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
      <PageHeader title="Billing" description="Your balance pays for workspaces. Top it up any time." />
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
            </div>
          </div>
          <div className="relative"><Button label="Add funds" icon={Plus} onClick={() => { setAdding(true); setNotice(""); }} /></div>
        </section>

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

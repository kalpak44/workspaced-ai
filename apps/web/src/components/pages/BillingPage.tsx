import { Download, Plus, Receipt, Wallet } from "lucide-react";
import { useState } from "react";
import { type Invoice, useBilling } from "../../hooks/useBilling";
import { formatMoney } from "../../lib/format";
import { Button, IconButton } from "../atoms";
import { EmptyState, PageHeader, SettingsSection } from "../molecules";
import { AddFundsDialog } from "../organisms";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium" });

const STATUS: Record<Invoice["status"], { readonly label: string; readonly className: string }> = {
  PAID: { label: "Paid", className: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-400" },
  PENDING: { label: "Pending", className: "bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-400" },
  FAILED: { label: "Failed", className: "bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-400" },
};

/** The account's balance, a way to top it up, and every invoice issued against it. */
export function BillingPage() {
  const { billing, status } = useBilling();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState("");

  if (status !== "ready" || !billing) {
    return (
      <>
        <PageHeader title="Billing" />
        {status === "failed"
          ? <EmptyState title="Billing is unavailable" hint="The balance could not be loaded. Please try again later." />
          : <EmptyState busy title="Loading billing…" hint="Fetching your balance and invoices" />}
      </>
    );
  }

  return (
    <>
      <PageHeader title="Billing" />
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
        <section aria-label="Balance" className="flex flex-col gap-4 rounded-xl border border-zinc-200 p-4 sm:flex-row sm:items-center sm:justify-between sm:p-6 dark:border-zinc-800">
          <div className="flex items-center gap-4">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200">
              <Wallet aria-hidden className="size-5" />
            </span>
            <div>
              <p className="text-sm text-zinc-500 dark:text-zinc-400">Current balance</p>
              <p className="text-3xl font-semibold tabular-nums">{formatMoney(billing.balanceCents)}</p>
            </div>
          </div>
          <Button label="Add funds" icon={Plus} onClick={() => { setAdding(true); setNotice(""); }} />
        </section>

        <SettingsSection title="Invoices" description="Every top-up, with a receipt to download.">
          {billing.invoices.length === 0 ? (
            <div className="flex flex-col items-center gap-2 py-8 text-center text-sm text-zinc-500 dark:text-zinc-400">
              <Receipt aria-hidden className="size-6" />
              No invoices yet.
            </div>
          ) : (
            <div className="-mx-4 overflow-x-auto sm:-mx-6">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-zinc-200 text-xs text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
                  <tr>
                    <th scope="col" className="px-4 py-2 font-medium sm:pl-6">Date</th>
                    <th scope="col" className="px-4 py-2 font-medium">Invoice</th>
                    <th scope="col" className="hidden px-4 py-2 font-medium md:table-cell">Description</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Amount</th>
                    <th scope="col" className="px-4 py-2 font-medium">Status</th>
                    <th scope="col" className="px-4 py-2 sm:pr-6"><span className="sr-only">Download</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                  {billing.invoices.map((invoice) => (
                    <tr key={invoice.id}>
                      <td className="whitespace-nowrap px-4 py-3 sm:pl-6">{DATE.format(new Date(invoice.issuedAt))}</td>
                      <td className="whitespace-nowrap px-4 py-3 font-mono text-xs">{invoice.number}</td>
                      <td className="hidden px-4 py-3 text-zinc-500 md:table-cell dark:text-zinc-400">{invoice.description}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums">{formatMoney(invoice.amountCents)}</td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex rounded px-1.5 py-0.5 text-xs font-medium ${STATUS[invoice.status].className}`}>{STATUS[invoice.status].label}</span>
                      </td>
                      <td className="px-4 py-3 text-right sm:pr-6">
                        {/* A failed payment issued no receipt, so there is nothing to download. */}
                        <IconButton label={`Download ${invoice.number}`} icon={Download} disabled={invoice.status !== "PAID"}
                          onClick={() => setNotice("Invoice downloads are not available yet.")} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SettingsSection>
        <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
      </div>
      {adding && (
        <AddFundsDialog
          balanceCents={billing.balanceCents}
          onClose={() => setAdding(false)}
          onConfirm={(cents) => { setAdding(false); setNotice(`Payments are not connected yet, so ${formatMoney(cents)} was not charged.`); }}
        />
      )}
    </>
  );
}

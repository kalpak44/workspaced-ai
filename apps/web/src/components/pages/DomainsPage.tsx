import { Check, Globe, Search, Settings2, X } from "lucide-react";
import { useState } from "react";
import { usePagination } from "../../hooks/usePagination";
import { useBilling } from "../../hooks/useBilling";
import { type DomainOffer, parseDomain, useDomains } from "../../hooks/useDomains";
import { navigate } from "../../hooks/useRoute";
import { formatMoney } from "../../lib/format";
import { Button, Spinner, Tag } from "../atoms";
import { EmptyState, PageHeader, Pagination, SettingsSection, Toast } from "../molecules";
import { BuyDomainDialog } from "../organisms";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium" });
const PAGE_SIZE = 10;

/** Search for a domain, buy one from the balance, and see the ones already owned. */
export function DomainsPage() {
  const { owned, status, offers, searching, search } = useDomains();
  const { billing } = useBilling();
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [buying, setBuying] = useState<DomainOffer | null>(null);
  const [notice, setNotice] = useState("");
  const domains = usePagination(owned, PAGE_SIZE);

  function submit() {
    const parsed = parseDomain(query);
    if (!parsed) {
      setError("Enter a name like my-site, or a full domain like my-site.de. Use letters, digits and hyphens, without a hyphen at either end.");
      return;
    }
    setError("");
    setNotice("");
    void search(parsed);
  }

  return (
    <>
      <PageHeader title="Domains" description="Find and register a domain, then point it at your workspaces." actions={owned.length > 0 && <Button label="DNS settings" icon={Settings2} variant="secondary" onClick={() => navigate({ name: "general-settings", section: "domains" })} />} />
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-4 pt-6 pb-10 sm:px-6">
        <SettingsSection title="Find a domain" description="Check any domain, such as my-site.de, or a name alone to see which endings are free.">
          <form role="search" className="flex flex-col gap-2 sm:flex-row" onSubmit={(event) => { event.preventDefault(); submit(); }}>
            <label className="field flex min-w-0 flex-1 items-center gap-2 px-3 focus-within:border-zinc-500 focus-within:ring-3 focus-within:ring-zinc-900/10 aria-invalid:border-rose-500 dark:focus-within:border-zinc-400 dark:focus-within:ring-white/10" aria-invalid={error ? true : undefined}>
              <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
              <input type="search" aria-label="Domain name" placeholder="my-site.com" value={query} maxLength={253}
                onChange={(event) => { setQuery(event.target.value); setError(""); }}
                className="min-h-10 w-full min-w-0 bg-transparent text-sm outline-none placeholder:text-zinc-400" />
            </label>
            <Button label="Search" type="submit" busy={searching} disabled={query.trim() === ""} />
          </form>
          {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
          {offers && (
            <ul aria-label="Search results" className="animate-fade-in divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {offers.map((offer) => (
                <li key={offer.name} className={`flex flex-wrap items-center gap-3 px-4 py-3 ${offer.available ? "" : "bg-zinc-50/60 dark:bg-zinc-900/40"}`}>
                  {offer.available
                    ? <Check aria-hidden className="size-4 shrink-0 text-emerald-500" />
                    : <X aria-hidden className="size-4 shrink-0 text-zinc-400" />}
                  <span className={`min-w-0 flex-1 truncate font-mono text-sm ${offer.available ? "" : "text-zinc-400 line-through"}`}>{offer.name}</span>
                  {offer.available ? (
                    <>
                      <span className="text-sm tabular-nums text-zinc-500 dark:text-zinc-400">{formatMoney(offer.priceCents)} / year</span>
                      <Button label={`Buy ${offer.name}`} variant="secondary" onClick={() => setBuying(offer)} />
                    </>
                  ) : <Tag label="Taken" />}
                </li>
              ))}
            </ul>
          )}
        </SettingsSection>

        <SettingsSection title="Your domains" description="Domains registered to this account.">
          {status === "loading" && <div className="flex justify-center py-6"><Spinner className="size-6 text-zinc-400" /></div>}
          {status === "failed" && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">Your domains could not be loaded.</p>}
          {status === "ready" && owned.length === 0 && (
            <EmptyState icon={Globe} title="No domains yet" hint="Search above to find one." />
          )}
          {status === "ready" && owned.length > 0 && (
            <ul aria-label="Your domains" className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {domains.items.map((domain) => (
                <li key={domain.name} className="flex flex-wrap items-center gap-3 py-3">
                  <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"><Globe className="size-4" /></span>
                  <span className="min-w-0 flex-1 truncate font-mono text-sm">{domain.name}</span>
                  <span className="text-xs text-zinc-500 dark:text-zinc-400">
                    {domain.autoRenew ? "Renews" : "Expires"} {DATE.format(new Date(domain.expiresAt))}
                  </span>
                  <Tag label={domain.autoRenew ? "Auto-renew" : "Manual renewal"} />
                  <a href="/settings/domains" aria-label={`Manage DNS for ${domain.name}`}
                    onClick={(event) => { event.preventDefault(); navigate({ name: "general-settings", section: "domains" }); }}
                    className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-white">
                    <Settings2 aria-hidden className="size-3.5" />Manage DNS
                  </a>
                </li>
              ))}
            </ul>
          )}
          {status === "ready" && (
            <Pagination label="Domain pages" noun="domain" page={domains.page} pageCount={domains.pageCount} onChange={domains.setPage}
              range={{ first: domains.first, last: domains.last, total: domains.total }} />
          )}
        </SettingsSection>
        <Toast message={notice} />
      </div>
      {buying && (
        <BuyDomainDialog
          name={buying.name}
          priceCents={buying.priceCents}
          balanceCents={billing?.balanceCents ?? null}
          onClose={() => setBuying(null)}
          onAddFunds={() => { setBuying(null); navigate({ name: "billing" }); }}
          onConfirm={() => { setBuying(null); setNotice(`Purchases are not connected yet, so ${buying.name} was not registered.`); }}
        />
      )}
    </>
  );
}

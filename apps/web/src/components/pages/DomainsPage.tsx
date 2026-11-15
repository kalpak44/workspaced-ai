import { Check, Globe, Search, X } from "lucide-react";
import { useState } from "react";
import { useBilling } from "../../hooks/useBilling";
import { type DomainOffer, parseDomain, useDomains } from "../../hooks/useDomains";
import { navigate } from "../../hooks/useRoute";
import { formatMoney } from "../../lib/format";
import { Button, Spinner, Tag } from "../atoms";
import { EmptyState, PageHeader, SettingsSection } from "../molecules";
import { BuyDomainDialog } from "../organisms";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium" });

/** Search for a domain, buy one from the balance, and see the ones already owned. */
export function DomainsPage() {
  const { owned, status, offers, searching, search } = useDomains();
  const { billing } = useBilling();
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [buying, setBuying] = useState<DomainOffer | null>(null);
  const [notice, setNotice] = useState("");

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
      <PageHeader title="Domains" />
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6 sm:px-6 sm:py-8">
        <SettingsSection title="Find a domain" description="Check any domain, such as my-site.de, or a name alone to see which endings are free.">
          <form role="search" className="flex flex-col gap-2 sm:flex-row" onSubmit={(event) => { event.preventDefault(); submit(); }}>
            <label className="flex min-w-0 flex-1 items-center gap-2 rounded-md border border-zinc-300 px-3 aria-invalid:border-rose-500 dark:border-zinc-700" aria-invalid={error ? true : undefined}>
              <Search aria-hidden className="size-4 shrink-0 text-zinc-400" />
              <input type="search" aria-label="Domain name" placeholder="my-site.com" value={query} maxLength={253}
                onChange={(event) => { setQuery(event.target.value); setError(""); }}
                className="min-h-10 w-full min-w-0 bg-transparent text-sm outline-none" />
            </label>
            <Button label="Search" type="submit" busy={searching} disabled={query.trim() === ""} />
          </form>
          {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
          {offers && (
            <ul aria-label="Search results" className="divide-y divide-zinc-200 rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {offers.map((offer) => (
                <li key={offer.name} className="flex flex-wrap items-center gap-3 px-4 py-3">
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
            <EmptyState title="No domains yet" hint="Search above to find one." />
          )}
          {status === "ready" && owned.length > 0 && (
            <ul aria-label="Your domains" className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {owned.map((domain) => (
                <li key={domain.name} className="flex flex-wrap items-center gap-3 py-3">
                  <Globe aria-hidden className="size-4 shrink-0 text-zinc-400" />
                  <span className="min-w-0 flex-1 truncate font-mono text-sm">{domain.name}</span>
                  <span className="text-xs text-zinc-500 dark:text-zinc-400">
                    {domain.autoRenew ? "Renews" : "Expires"} {DATE.format(new Date(domain.expiresAt))}
                  </span>
                  <Tag label={domain.autoRenew ? "Auto-renew" : "Manual renewal"} />
                </li>
              ))}
            </ul>
          )}
        </SettingsSection>
        <p role="status" className="min-h-5 text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>
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

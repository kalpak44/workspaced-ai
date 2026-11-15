import { useEffect, useRef, useState } from "react";
import { type DomainOffer, listOwnedDomains, type OwnedDomain, searchDomains } from "../api/domainsApi";

export type { DomainOffer, OwnedDomain } from "../api/domainsApi";

// Letters, digits and inner hyphens, as a registry accepts for one label.
const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
// One or two letter-only parts, so "de" and "co.uk" both pass.
const ENDING = /^[a-z]{2,63}(?:\.[a-z]{2,63})?$/;

/** A searchable domain: the name, and the ending typed with it, if any. */
export interface DomainQuery {
  readonly label: string;
  readonly tld: string | null;
}

/** Reads "https://Shop.co.uk/" as shop + co.uk, and "shop" alone as a name to suggest endings for; null when invalid. */
export function parseDomain(input: string): DomainQuery | null {
  const host = input.trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/.*$/, "").replace(/^www\./, "");
  const [label = "", ...rest] = host.split(".");
  const tld = rest.length > 0 ? rest.join(".") : null;
  return LABEL.test(label) && (tld === null || ENDING.test(tld)) ? { label, tld } : null;
}

export function useDomains() {
  const [owned, setOwned] = useState<OwnedDomain[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const [offers, setOffers] = useState<DomainOffer[] | null>(null);
  const [searching, setSearching] = useState(false);
  // Only the latest search may fill the results, however the earlier ones finish.
  const generation = useRef(0);

  useEffect(() => {
    let active = true;
    listOwnedDomains().then(
      (list) => {
        if (active) {
          setOwned(list);
          setStatus("ready");
        }
      },
      () => active && setStatus("failed"),
    );
    return () => {
      active = false;
    };
  }, []);

  async function search({ label, tld }: DomainQuery) {
    const current = ++generation.current;
    setSearching(true);
    try {
      const result = await searchDomains(label, tld);
      if (current === generation.current) setOffers(result);
    } finally {
      if (current === generation.current) setSearching(false);
    }
  }

  return { owned, status, offers, searching, search };
}

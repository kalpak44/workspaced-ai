import { useEffect, useState } from "react";
import { type Billing, fetchBilling } from "../api/billingApi";

export type { Billing, Invoice } from "../api/billingApi";

/** Fetched once per mount, so the account menu and the billing page each show a fresh balance. */
export function useBilling() {
  const [billing, setBilling] = useState<Billing | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");

  useEffect(() => {
    let active = true;
    fetchBilling().then(
      (result) => {
        if (active) {
          setBilling(result);
          setStatus("ready");
        }
      },
      () => active && setStatus("failed"),
    );
    return () => {
      active = false;
    };
  }, []);

  return { billing, status };
}

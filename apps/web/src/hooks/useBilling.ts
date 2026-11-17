import { useCallback, useEffect, useState } from "react";
import { type Billing, type TopUp, browser, fetchBilling, invoiceUrl, refreshTopUp, startTopUp } from "../api/billingApi";
import { ApiError } from "../api/http";

export type { Billing, DailySpend, TopUp, WorkspaceSpend } from "../api/billingApi";

/** Fetched once per mount, so the account menu and the billing page each show a fresh balance. */
export function useBilling() {
  const [billing, setBilling] = useState<Billing | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const [version, setVersion] = useState(0);

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
  }, [version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  /** Leaves for Stripe's payment page; resolves only when it could not be opened. */
  const pay = useCallback(async (amountCents: number): Promise<"unavailable" | "failed" | "redirected"> => {
    try {
      browser.open((await startTopUp(amountCents)).checkoutUrl);
      return "redirected";
    } catch (error) {
      return error instanceof ApiError && error.status === 503 ? "unavailable" : "failed";
    }
  }, []);

  /** Where a top-up stands once the browser is back from Stripe, with the balance reloaded. */
  const confirm = useCallback(async (id: string): Promise<TopUp> => {
    const topUp = await refreshTopUp(id);
    reload();
    return topUp;
  }, [reload]);

  const openInvoice = useCallback((id: string) => browser.openTab(invoiceUrl(id)), []);

  return { billing, status, reload, pay, confirm, openInvoice };
}

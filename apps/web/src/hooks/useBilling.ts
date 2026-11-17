import { useCallback, useEffect, useState } from "react";
import {
  type AutoTopUp,
  type Billing,
  type TopUp,
  browser,
  confirmCard as keepCard,
  fetchBilling,
  invoiceUrl,
  refreshTopUp,
  removeCard as forgetCard,
  saveAutoTopUp,
  startCardSetup,
  startTopUp,
} from "../api/billingApi";
import { ApiError } from "../api/http";

export type { AutoTopUp, Billing, DailySpend, TopUp, WorkspaceSpend } from "../api/billingApi";

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

  /** Leaves for Stripe's page that saves a card; resolves only when it could not be opened. */
  const saveCard = useCallback(async (): Promise<"unavailable" | "failed" | "redirected"> => {
    try {
      browser.open(await startCardSetup());
      return "redirected";
    } catch (error) {
      return error instanceof ApiError && error.status === 503 ? "unavailable" : "failed";
    }
  }, []);

  /** Keeps the card once the browser is back from Stripe, with the billing reloaded. */
  const confirmCard = useCallback(async (sessionId: string) => {
    await keepCard(sessionId);
    reload();
  }, [reload]);

  const removeCard = useCallback(async () => {
    await forgetCard();
    reload();
  }, [reload]);

  const setAutoTopUp = useCallback(async (settings: AutoTopUp) => {
    await saveAutoTopUp(settings);
    reload();
  }, [reload]);

  return { billing, status, reload, pay, confirm, openInvoice, saveCard, confirmCard, removeCard, setAutoTopUp };
}

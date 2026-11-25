import { useCallback, useEffect, useState } from "react";
import { getPlanWaiting, listVmSizes, type VmSize, waitForPlan } from "../api/vmSizesApi";

export type { VmSize } from "../api/vmSizesApi";

/**
 * Fetched once per mount, which is each time the create dialog opens, and again on `reload`, since
 * Hetzner's capacity comes and goes while the dialog is open.
 */
export function useVmSizes() {
  const [sizes, setSizes] = useState<VmSize[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const [attempt, setAttempt] = useState(0);
  // Whether the user asked to be emailed once a plan is back; unknown counts as not.
  const [waiting, setWaiting] = useState(false);

  useEffect(() => {
    let active = true;
    getPlanWaiting().then((value) => active && setWaiting(value), () => undefined);
    listVmSizes().then(
      (list) => {
        if (active) {
          setSizes(list);
          setStatus("ready");
        }
      },
      () => active && setStatus("failed"),
    );
    return () => {
      active = false;
    };
  }, [attempt]);

  const reload = useCallback(() => {
    setStatus("loading");
    setAttempt((value) => value + 1);
  }, []);

  const wait = useCallback(async () => {
    await waitForPlan();
    setWaiting(true);
  }, []);

  return { sizes, status, reload, waiting, wait };
}

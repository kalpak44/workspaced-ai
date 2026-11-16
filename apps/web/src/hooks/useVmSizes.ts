import { useCallback, useEffect, useState } from "react";
import { listVmSizes, type VmSize } from "../api/vmSizesApi";

export type { VmSize } from "../api/vmSizesApi";

/**
 * Fetched once per mount, which is each time the create dialog opens, and again on `reload`, since
 * Hetzner's capacity comes and goes while the dialog is open.
 */
export function useVmSizes() {
  const [sizes, setSizes] = useState<VmSize[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
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

  return { sizes, status, reload };
}

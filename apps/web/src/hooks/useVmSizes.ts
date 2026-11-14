import { useEffect, useState } from "react";
import { listVmSizes, type VmSize } from "../api/vmSizesApi";

export type { VmSize } from "../api/vmSizesApi";

/** Fetched once per mount, which is each time the create dialog opens. */
export function useVmSizes() {
  const [sizes, setSizes] = useState<VmSize[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");

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
  }, []);

  return { sizes, status };
}

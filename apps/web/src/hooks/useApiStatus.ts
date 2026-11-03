import { useEffect, useState } from "react";
import { type ApiStatus, fetchApiStatus } from "../api/apiStatus";

export type { ApiStatus } from "../api/apiStatus";

/** Re-checks the API each time `active` turns true, e.g. when a menu showing it opens. */
export function useApiStatus(active: boolean): ApiStatus {
  const [status, setStatus] = useState<ApiStatus>("checking");

  useEffect(() => {
    if (active) {
      setStatus("checking");
      fetchApiStatus().then(setStatus, () => setStatus("unreachable"));
    }
  }, [active]);

  return status;
}

import { useEffect, useState } from "react";
import { listModelProviders, type ModelProvider } from "../api/modelProvidersApi";

export type { ModelProvider } from "../api/modelProvidersApi";

/** Fetched once per mount; the list is fixed by the API build, so it never changes under us. */
export function useModelProviders() {
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");

  useEffect(() => {
    let active = true;
    listModelProviders().then(
      (list) => {
        if (active) {
          setProviders(list);
          setStatus("ready");
        }
      },
      () => active && setStatus("failed"),
    );
    return () => {
      active = false;
    };
  }, []);

  return { providers, status };
}

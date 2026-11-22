import { useEffect, useState } from "react";
import { createAiConnector, deleteAiConnector, listAiConnectors, type AiConnector } from "../api/aiConnectorsApi";
import { ApiError } from "../api/http";

export const isRejectedConnectorKey = (cause: unknown) => cause instanceof ApiError && cause.status === 422;

export function useAiConnectors() {
  const [connectors, setConnectors] = useState<AiConnector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    listAiConnectors().then(list => { if (active) setConnectors(list); })
      .catch(() => { if (active) setError("Could not load AI connectors. Please try again."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const add = async (name: string, provider: string, key: string, accepted: boolean) => {
    const saved = await createAiConnector(name, provider, key, accepted);
    setConnectors(current => [...current, saved]);
    return saved;
  };
  const remove = async (id: string) => {
    await deleteAiConnector(id);
    setConnectors(current => current.filter(connector => connector.id !== id));
  };
  return { connectors, add, remove, loading, error };
}

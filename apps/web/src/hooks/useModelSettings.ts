import { useEffect, useState } from "react";
import { listModels, type ModelCatalogue, setModel } from "../api/settingsApi";

export type { ModelCatalogue, ModelOption } from "../api/settingsApi";

/**
 * The models a workspace's agent can use, and the one it does. The catalogue lives in the
 * harness, so pass null while the workspace is not running.
 */
export function useModelSettings(
  workspaceId: string | null,
  current: string | null,
  onChanged: (model: string | null) => void,
) {
  const [catalogue, setCatalogue] = useState<ModelCatalogue | null>(null);
  const [loading, setLoading] = useState(workspaceId !== null);
  const [error, setError] = useState<string | null>(null);
  // The model a save is in flight for, so the list can show which row is saving.
  const [saving, setSaving] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    setCatalogue(null);
    setError(null);
    if (workspaceId === null) {
      setLoading(false);
      return undefined;
    }
    let active = true;
    setLoading(true);
    listModels(workspaceId)
      .then((list) => active && setCatalogue(list))
      .catch(() => active && setError("Could not load the models"))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [workspaceId]);

  const choose = (model: string | null) => {
    if (workspaceId === null || model === current) {
      return;
    }
    setSaving(model);
    setError(null);
    setModel(workspaceId, model).then(
      () => {
        onChanged(model);
        setSaving(undefined);
      },
      () => {
        setSaving(undefined);
        setError("Could not change the model");
      },
    );
  };

  return { catalogue, loading, error, saving, dismissError: () => setError(null), choose };
}

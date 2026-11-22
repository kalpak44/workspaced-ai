import { listVmSizes } from "../api/vmSizesApi";
import { completeWorkspaceTutorial } from "../api/workspacesApi";
import { refreshNotifications } from "./useNotifications";
export type { VmSize } from "../api/vmSizesApi";

/** Only trial provisioning is custom. Other tour mutations use the app's actual forms. */
export async function loadTrialMachines() { return listVmSizes(); }
export async function finishTour(workspaceId: string): Promise<boolean> {
  try {
    await completeWorkspaceTutorial(workspaceId);
    await refreshNotifications();
    return true;
  } catch { return false; }
}

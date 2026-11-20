import { getFirewall } from "../api/firewallApi";
import { listSecrets } from "../api/secretsApi";
import { listVmSizes } from "../api/vmSizesApi";
import { completeWorkspaceTutorial, listWorkspaces } from "../api/workspacesApi";
import { refreshNotifications } from "./useNotifications";
export type { VmSize } from "../api/vmSizesApi";

/** Only trial provisioning is custom. Other tour mutations use the app's actual forms. */
export async function loadTrialMachines() { return listVmSizes(); }
export async function loadTrialSecretNames(workspaceId: string): Promise<string[]> {
  return (await listSecrets(workspaceId)).map((secret) => secret.name);
}
export async function trialPortIsOpen(workspaceId: string, port: string): Promise<boolean> {
  return (await getFirewall(workspaceId)).rules.some((rule) => rule.protocol === "tcp" && rule.port === port
    && (rule.sourceIps.length === 0 || rule.sourceIps.includes("0.0.0.0/0")));
}
export async function isTrialReady(workspaceId: string): Promise<boolean> {
  const workspace = (await listWorkspaces()).find((item) => item.id === workspaceId);
  return workspace?.status === "READY" && !workspace.restartRequired;
}
export async function finishTour(workspaceId: string): Promise<boolean> {
  try {
    await completeWorkspaceTutorial(workspaceId);
    await refreshNotifications();
    return true;
  } catch { return false; }
}

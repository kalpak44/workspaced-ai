import { fetchRecipe } from "../api/recipesApi";
import { listMessages } from "../api/sessionsApi";
import { applyPrompt, authorName } from "../lib/recipes";
import { TOUR_RECIPE_ID } from "../lib/tour";
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

/** Check authoritative history before retrying a recipe whose first request failed. */
export async function recoverTourChat(workspaceId: string, sessionId: string, building: boolean,
  send: (text: string) => Promise<boolean>, reconnect: () => Promise<void>): Promise<void> {
  const history = await listMessages(workspaceId, sessionId);
  if (building && history.length === 0) {
    const recipe = await fetchRecipe(TOUR_RECIPE_ID);
    await send(applyPrompt(recipe.title, authorName(recipe.author), recipe.content));
  } else await reconnect();
}

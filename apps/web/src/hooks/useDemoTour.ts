import { addSecret, listSecrets } from "../api/secretsApi";
import { listVmSizes } from "../api/vmSizesApi";
import { listWorkspaces, restartWorkspace } from "../api/workspacesApi";
export type { VmSize } from "../api/vmSizesApi";

import { fetchRecipe } from "../api/recipesApi";
import { createSession } from "../api/sessionsApi";
import { saveTask } from "../api/tasksApi";
import { applyPrompt, authorName } from "../lib/recipes";
import { TOUR_RECIPE_ID, TOUR_TASK } from "../lib/tour";
import { queuePrompt } from "./useChat";

/**
 * Applies the tour's recipe to the demo the way the store's Apply button does, and answers the
 * session it started; null when the recipe or the session could not be had.
 */
export async function applyTourRecipe(workspaceId: string): Promise<string | null> {
  try {
    const recipe = await fetchRecipe(TOUR_RECIPE_ID);
    const session = await createSession(workspaceId);
    queuePrompt(session.id, applyPrompt(recipe.title, authorName(recipe.author), recipe.content));
    return session.id;
  } catch {
    return null;
  }
}

/** Saves the tour's scheduled task as the Tasks page would; false when the workspace refused it. */
export async function scheduleTourTask(workspaceId: string, timeZone: string): Promise<boolean> {
  try {
    await saveTask(workspaceId, TOUR_TASK.name, { cron: TOUR_TASK.cron, timeZone, prompt: TOUR_TASK.prompt });
    return true;
  } catch {
    return false;
  }
}

/** The setup operations shared by the guided creation and credentials screens. */
export async function loadTrialMachines() {
  return listVmSizes();
}

export async function loadTrialSecretNames(workspaceId: string): Promise<string[]> {
  return (await listSecrets(workspaceId)).map((secret) => secret.name);
}

export async function addTrialSecret(workspaceId: string, name: string, value: string) {
  await addSecret(workspaceId, name, value);
}

export async function restartTrialWorkspace(workspaceId: string) {
  await restartWorkspace(workspaceId);
}

export async function isTrialReady(workspaceId: string): Promise<boolean> {
  const workspace = (await listWorkspaces()).find((item) => item.id === workspaceId);
  return workspace?.status === "READY" && !workspace.restartRequired;
}

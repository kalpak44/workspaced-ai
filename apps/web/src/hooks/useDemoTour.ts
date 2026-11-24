import { fetchRecipe } from "../api/recipesApi";
import type { AgentForm, FormAnswer } from "../api/sessionsApi";
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

/** Only the recipe's single public-access question has a predefined answer. */
export function publicTourAnswer(forms: readonly AgentForm[]): { id: string; answer: FormAnswer } | null {
  if (forms.length !== 1) return null;
  const form = forms[0];
  if (form.fields.length !== 1) return null;
  const field = form.fields[0];
  if (field.hidden || field.when?.length || !/\b(public|publish|online|go live|internet|anyone)\b/i.test(`${form.title} ${field.title ?? ""} ${field.description ?? ""}`)) return null;
  if (field.type === "boolean") return { id: form.id, answer: { [field.key]: true } };
  if (field.type !== "string") return null;
  const yes = field.options?.find(option => /^(yes|publish|allow|go ahead|proceed|confirm)\b/i.test(option.label)
    && !/\b(no|not|private|later|cancel)\b/i.test(option.label));
  if (yes) return { id: form.id, answer: { [field.key]: yes.value } };
  if (!field.options?.length && !field.pattern && (field.minLength ?? 0) <= 3 && (field.maxLength ?? 3) >= 3) {
    return { id: form.id, answer: { [field.key]: "Yes" } };
  }
  return null;
}

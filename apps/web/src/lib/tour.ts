import { useSyncExternalStore } from "react";
import { readStored, writeStored } from "./storage";

export const TOUR_STEP = {
  WELCOME: 0, NAME: 1, OPENROUTER: 2, MACHINE: 3, STARTING: 4,
  STORE: 5, PRIVATE: 6, OWN: 7, PUBLIC: 8, FIND_RECIPE: 9, REVIEW: 10, APPLY: 11, PICK: 12,
  BUILD: 13, CONFIRM_PUBLIC: 14, DEPLOY: 15, VERIFY: 16, DESIGN: 17, DONE: 18,
} as const;
export const TOUR_STEP_COUNT = Object.keys(TOUR_STEP).length;
export const TOUR_WORKSPACE_NAME = "My first workspace";
export const TOUR_PUBLIC_PROMPT = "Yes, make this page public so anyone can open it from the internet. Open the website port, publish the page, and give me its clickable address.";
export const TOUR_DESIGN_PROMPT = "Change the heading to Welcome to my first workspace and use a warm cream background. Keep the page public at the same address.";
export const TOUR_DOCS_URL = "https://workspaced.online/docs";

/** Only non-secret identifiers and names are persisted. */
export interface TourProgress {
  readonly step: number;
  readonly workspaceId?: string;
  readonly sessionId?: string;
  readonly draftId?: string;
  readonly name?: string;
}

// Public Workspaced demo recipe, seeded by the API with a fixed id.
export const TOUR_RECIPE_ID = "00000000-0000-4000-8000-00000000b00d";

const KEY = "workspaced.tour.v6";
let progress: TourProgress | null = load();
const listeners = new Set<() => void>();
const actionListeners = new Set<(action: TourAction) => void>();

function load(): TourProgress | null {
  try {
    const stored = readStored(KEY);
    if (!stored) {
      const previousPageTour = readStored("workspaced.tour.v5");
      if (previousPageTour) {
        const old = JSON.parse(previousPageTour) as TourProgress;
        if (!Number.isInteger(old.step) || old.step < 0 || old.step > 24
            || ![old.workspaceId, old.sessionId, old.draftId, old.name].every(item => item === undefined || typeof item === "string")) return null;
        const migrated = { ...old, step: old.step <= 13 ? old.step : old.step <= 20 ? TOUR_STEP.CONFIRM_PUBLIC : old.step - 6 };
        writeStored(KEY, JSON.stringify(migrated));
        writeStored("workspaced.tour.v5", "");
        return migrated;
      }
      // Old tours run a different recipe. Keep the workspace, but never replay Apply
      // or pretend the WordPress conversation belongs to the new demo.
      for (const version of [4, 3, 2]) {
        const previous = readStored(`workspaced.tour.v${version}`);
        if (!previous) continue;
        const old = JSON.parse(previous) as TourProgress;
        if (!Number.isInteger(old.step) || old.step < 0 || old.step > (version === 4 ? 50 : version === 3 ? 51 : 12)) return null;
        const migrated: TourProgress = typeof old.workspaceId === "string"
          ? { step: TOUR_STEP.STORE, workspaceId: old.workspaceId }
          : { step: typeof old.draftId === "string" ? TOUR_STEP.OPENROUTER : TOUR_STEP.WELCOME,
              ...(typeof old.draftId === "string" ? { draftId: old.draftId } : {}),
              ...(typeof old.name === "string" ? { name: old.name } : {}) };
        writeStored(KEY, JSON.stringify(migrated));
        for (const previousVersion of [2, 3, 4]) writeStored(`workspaced.tour.v${previousVersion}`, "");
        return migrated;
      }
      return null;
    }
    const value = JSON.parse(stored) as TourProgress;
    const valid = Number.isInteger(value.step) && value.step >= 0 && value.step < TOUR_STEP_COUNT
      && [value.workspaceId, value.sessionId, value.draftId, value.name]
        .every((item) => item === undefined || typeof item === "string");
    if (!valid) return null;
    return value;
  } catch { return null; }
}
function emit() { listeners.forEach((listener) => listener()); }
function subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
export function useTourProgress(): TourProgress | null { return useSyncExternalStore(subscribe, () => progress); }
export function setTourProgress(next: TourProgress | null): void {
  progress = next;
  writeStored(KEY, next ? JSON.stringify(next) : "");
  emit();
}

/** An accepted action from an actual app control, never a guessed click outcome. */
export interface TourAction {
  readonly type: string;
  readonly workspaceId?: string;
  readonly sessionId?: string;
  readonly name?: string;
  readonly value?: string;
}
export function reportTourAction(action: TourAction): void {
  if (progress) actionListeners.forEach((listener) => listener(action));
}
export function subscribeTourActions(listener: (action: TourAction) => void): () => void {
  actionListeners.add(listener);
  return () => { actionListeners.delete(listener); };
}

const busy = new Map<string, boolean>();
export function reportChatBusy(sessionId: string, working: boolean): void {
  if (busy.get(sessionId) === working) return;
  busy.set(sessionId, working); emit();
}
export function useChatBusy(sessionId: string | undefined): boolean | undefined {
  return useSyncExternalStore(subscribe, () => sessionId ? busy.get(sessionId) : undefined);
}

type ChatCheckpoint = "public";
const checkpoints = new Map<string, ChatCheckpoint | undefined>();
/** Wait for the assistant to explain public access before asking the user to confirm. */
export function reportChatCheckpoint(sessionId: string, text: string): void {
  const checkpoint = /\bpublic\b/i.test(text) && /\binternet\b/i.test(text) ? "public" : undefined;
  if (checkpoints.get(sessionId) === checkpoint) return;
  checkpoints.set(sessionId, checkpoint); emit();
}
export function useChatCheckpoint(sessionId: string | undefined): ChatCheckpoint | undefined {
  return useSyncExternalStore(subscribe, () => sessionId ? checkpoints.get(sessionId) : undefined);
}

/** Uses React's native value setter. Stops if the user edits or moves away during a demonstration. */
export async function typeInto(field: HTMLTextAreaElement | HTMLInputElement, text: string, delayMs = 18, signal?: AbortSignal): Promise<boolean> {
  const prototype = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setValue = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  let previous = field.value;
  field.focus({ preventScroll: true });
  const reduced = globalThis.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  for (let length = reduced ? text.length : 1; length <= text.length; length += 1) {
    if (!field.isConnected || signal?.aborted || field.value !== previous) return false;
    previous = text.slice(0, length);
    setValue?.call(field, previous);
    field.dispatchEvent(new Event("input", { bubbles: true }));
    if (!reduced && delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  return true;
}

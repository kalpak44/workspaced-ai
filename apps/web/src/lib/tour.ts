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
export const TOUR_DESIGN_IDEAS: readonly string[] = ["Make it about my bakery, with opening hours", "Use calm, dark colours", "Add a short section about me", "Make it more playful"];
export const TOUR_DESIGN_PROMPT = "Change the heading to Welcome to my first workspace and use a warm cream background. Keep the page public at the same address.";

/** Kept on the account, so any browser resumes it. Only non-secret identifiers and names. */
export interface TourProgress {
  readonly step: number;
  readonly workspaceId?: string;
  readonly sessionId?: string;
  readonly draftId?: string;
  readonly name?: string;
  /** Skipped for now: hidden until the user resumes it, rather than started again. */
  readonly paused?: boolean;
}

// Public Workspaced demo recipe, seeded by the API with a fixed id.
export const TOUR_RECIPE_ID = "00000000-0000-4000-8000-00000000b00d";

// Where this browser kept the tour before the account did; read once and moved to the account.
const KEY = "workspaced.tour.v6";
let progress: TourProgress | null = load();
let adopted = false;
let save: (progress: TourProgress | null) => Promise<void> = async () => undefined;
let pendingSave: Promise<void> = Promise.resolve();
/** How the tour reaches the account; the account hook provides it. */
export function saveTourWith(saver: typeof save): void { save = saver; }
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
const active = () => (progress?.paused ? null : progress);
/** The tour under way; null while none is, or it is paused. */
export function useTourProgress(): TourProgress | null { return useSyncExternalStore(subscribe, active); }
/** The tour as the account keeps it, paused or not. */
export function useStoredTour(): TourProgress | null { return useSyncExternalStore(subscribe, () => progress); }
export function setTourProgress(next: TourProgress | null): void {
  progress = next;
  emit();
  // Preserve step order even when requests complete at different speeds.
  const saver = save;
  pendingSave = pendingSave.then(() => saver(next)).catch(() => undefined);
}
/**
 * Takes the account's tour once it loads. With none there, a tour this browser already has, from
 * before the account kept it, is moved onto the account instead.
 */
export function adoptTourProgress(stored: TourProgress | null): void {
  if (adopted) return;
  adopted = true;
  writeStored(KEY, "");
  if (stored) { progress = stored; emit(); }
  else if (progress) setTourProgress(progress);
}
/** Forgets the tour in this tab without telling the account; for tests. */
export function resetTour(): void {
  progress = null;
  adopted = false;
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
  if (active()) actionListeners.forEach((listener) => listener(action));
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

interface TourChatIssue {
  readonly message: string;
  readonly retry: () => Promise<void>;
}
const chatIssues = new Map<string, TourChatIssue>();
export function reportTourChatIssue(sessionId: string, issue: TourChatIssue | null): void {
  const previous = chatIssues.get(sessionId);
  if (issue) chatIssues.set(sessionId, issue); else chatIssues.delete(sessionId);
  if (previous?.message !== issue?.message) emit();
}
export function useTourChatIssue(sessionId: string | undefined): TourChatIssue | undefined {
  return useSyncExternalStore(subscribe, () => sessionId ? chatIssues.get(sessionId) : undefined);
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

// The address the assistant publishes a page on: its machine's IP, as the recipe instructs.
const PAGE_LINK = /\bhttps?:\/\/\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?:\/[^\s)\]>"'`]*)?/;
const pageLinks = new Map<string, string>();
/** The last public page address the assistant gave in this conversation, once it gives one. */
export function reportChatPageLink(sessionId: string, text: string): void {
  const link = PAGE_LINK.exec(text)?.[0].replace(/[.,;:!?]+$/, "");
  if (!link || pageLinks.get(sessionId) === link) return;
  pageLinks.set(sessionId, link); emit();
}
export function useChatPageLink(sessionId: string | undefined): string | undefined {
  return useSyncExternalStore(subscribe, () => sessionId ? pageLinks.get(sessionId) : undefined);
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

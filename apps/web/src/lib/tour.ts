import { useSyncExternalStore } from "react";
import { readStored, writeStored } from "./storage";

export const TOUR_STEP = {
  WELCOME: 0, NAME: 1, OPENROUTER: 2, MACHINE: 3, STARTING: 4,
  STORE: 5, PRIVATE: 6, OWN: 7, PUBLIC: 8, FIND_RECIPE: 9, REVIEW: 10, APPLY: 11, PICK: 12,
  REQUEST_SECRETS: 13, SETTINGS: 14, SECRETS: 15, ADMIN_USER: 16, ADMIN_PASSWORD: 17,
  DB_PASSWORD: 18, RESTART: 19, CONFIRM_RESTART: 20, RESTARTING: 21, SESSIONS: 22,
  SESSION: 23, RESUME_SECRETS: 24, BUILD: 25, PORT_SETTINGS: 26, FIREWALL: 27,
  HTTP: 28, HTTPS: 29, PORT_REVIEW: 30, PORT_SESSIONS: 31, PORT_SESSION: 32,
  RESUME_PORTS: 33, DEPLOY: 34, VERIFY: 35, TASK_SETTINGS: 36, TASKS: 37,
  NEW_TASK: 38, TASK_NAME: 39, TASK_CONFIG: 40, TASK_SAVED: 41, INSTRUCTIONS: 42,
  BLOG_GUIDE: 43, NEW_INSTRUCTION: 44, INSTRUCTION_NAME: 45, INSTRUCTION_EDIT: 46,
  INSTRUCTION_SAVED: 47, DESIGN_SESSIONS: 48, DESIGN_SESSION: 49, DESIGN: 50, DONE: 51,
} as const;
export const TOUR_STEP_COUNT = Object.keys(TOUR_STEP).length;
export const TOUR_WORKSPACE_NAME = "My WordPress trial";
export const TOUR_STYLE_NAME = "writing-style";
export const TOUR_STYLE = "Use short, friendly sentences. Explain technical terms in plain language. " +
  "For news articles, include the source link and publication date. Never invent facts or quotes.";
export const TOUR_SECRET_CONTINUE = "The WordPress admin and database secrets are saved and the workspace has restarted. " +
  "Continue the recipe to its port-opening checkpoint. I will open ports 80 and 443 myself in Settings.";
export const TOUR_PORT_CONTINUE = "TCP ports 80 and 443 are now open in Settings. Verify them, then continue the recipe: " +
  "obtain the Let's Encrypt IP certificate, publish the blog over HTTPS, write the shared blog instructions and verify the site.";

/** Only non-secret identifiers and names are persisted. */
export interface TourProgress {
  readonly step: number;
  readonly workspaceId?: string;
  readonly sessionId?: string;
  readonly draftId?: string;
  readonly name?: string;
  readonly taskName?: string;
  readonly instructionName?: string;
}

// The starter WordPress recipe, seeded by the API with a fixed id.
export const TOUR_RECIPE_ID = "00000000-0000-4000-8000-00000000b001";

export const TOUR_DESIGN_PROMPT =
  "Give the blog a warmer look: a deep green and cream colour scheme, a serif font for headings, " +
  "and a short \"About this blog\" box in the sidebar. Keep everything else as it is.";

export const TOUR_TASK = {
  name: "tech-news",
  cron: "*/5 * * * *",
  prompt:
    "Read a current technology news RSS feed. Choose one recent story not already covered on " +
    "the WordPress blog in ~/project/blog. Write an original summary of about 150 words, link " +
    "to the source and include its publication date. Verify the source; never invent news. " +
    "Read ~/.config/workspaced/instructions/blog.md and publish using its tested WP-CLI " +
    "commands, preserving the HTTPS site URL. If there is no new verified story, skip this run.",
} as const;

const KEY = "workspaced.tour.v3";
let progress: TourProgress | null = load();
const listeners = new Set<() => void>();
const actionListeners = new Set<(action: TourAction) => void>();

function load(): TourProgress | null {
  try {
    const stored = readStored(KEY);
    if (!stored) {
      const previous = readStored("workspaced.tour.v2");
      if (!previous) return null;
      const old = JSON.parse(previous) as TourProgress;
      writeStored("workspaced.tour.v2", "");
      if (!Number.isInteger(old.step) || old.step < 0 || old.step > 12) return null;
      const migrated: TourProgress = old.workspaceId && typeof old.workspaceId === "string"
        ? { ...old, step: old.step === 12 ? TOUR_STEP.DONE : TOUR_STEP.STARTING }
        : { step: old.draftId ? TOUR_STEP.OPENROUTER : TOUR_STEP.WELCOME, draftId: old.draftId };
      writeStored(KEY, JSON.stringify(migrated));
      return migrated;
    }
    const value = JSON.parse(stored) as TourProgress;
    return Number.isInteger(value.step) && value.step >= 0 && value.step < TOUR_STEP_COUNT
      && [value.workspaceId, value.sessionId, value.draftId, value.name, value.taskName, value.instructionName]
        .every((item) => item === undefined || typeof item === "string") ? value : null;
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

type ChatCheckpoint = "secrets" | "ports";
const checkpoints = new Map<string, ChatCheckpoint | undefined>();
/** Only the latest completed assistant reply can unlock a Settings checkpoint. */
export function reportChatCheckpoint(sessionId: string, text: string): void {
  const checkpoint = /settings/i.test(text) && /secrets/i.test(text)
    && ["WORDPRESS_ADMIN_USER", "WORDPRESS_ADMIN_PASSWORD", "WORDPRESS_DB_PASSWORD"].every((name) => text.includes(name)) ? "secrets"
    : /settings/i.test(text) && /firewall/i.test(text) && /\b80\b/.test(text) && /\b443\b/.test(text) ? "ports" : undefined;
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

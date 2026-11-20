import { useSyncExternalStore } from "react";
import { readStored, writeStored } from "./storage";

/**
 * The guided tour of the demo workspace: where it is, kept across reloads, and what the chat page
 * reports for it to wait on. The tour itself is DemoTour; this is only what other pages share
 * with it.
 */
export interface TourProgress {
  readonly step: number;
  readonly workspaceId?: string;
  readonly sessionId?: string;
  readonly draftId?: string;
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
    "Publish using the blog.md instructions. If there is no new verified story, skip this run.",
} as const;

const KEY = "workspaced.tour.v2";

let progress: TourProgress | null = load();
const listeners = new Set<() => void>();

function load(): TourProgress | null {
  try {
    const stored = readStored(KEY);
    if (!stored) return null;
    const value = JSON.parse(stored) as TourProgress;
    return Number.isInteger(value.step) && value.step >= 0 && value.step < 13
      && [value.workspaceId, value.sessionId, value.draftId].every((id) => id === undefined || typeof id === "string")
      ? value : null;
  } catch {
    return null;
  }
}

function emit() {
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Null while no tour is running. */
export function useTourProgress(): TourProgress | null {
  return useSyncExternalStore(subscribe, () => progress);
}

export function setTourProgress(next: TourProgress | null): void {
  progress = next;
  writeStored(KEY, next ? JSON.stringify(next) : "");
  emit();
}

// Whether each session's agent is working, as the open chat page last saw it.
const busy = new Map<string, boolean>();

/** Called by the chat page, so the tour can wait for the agent without a stream of its own. */
export function reportChatBusy(sessionId: string, working: boolean): void {
  if (busy.get(sessionId) === working) return;
  busy.set(sessionId, working);
  emit();
}

/** Undefined until the chat page for that session has said anything. */
export function useChatBusy(sessionId: string | undefined): boolean | undefined {
  return useSyncExternalStore(subscribe, () => (sessionId ? busy.get(sessionId) : undefined));
}

/**
 * Types into a field one character at a time, through the setter React listens to, so the page's
 * own state follows as if a person typed. Resolves false when the field went away mid-way.
 */
export async function typeInto(field: HTMLTextAreaElement | HTMLInputElement, text: string, delayMs = 18): Promise<boolean> {
  const prototype = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setValue = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
  field.focus();
  for (let length = 1; length <= text.length; length += 1) {
    if (!field.isConnected) return false;
    setValue?.call(field, text.slice(0, length));
    field.dispatchEvent(new Event("input", { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  return true;
}

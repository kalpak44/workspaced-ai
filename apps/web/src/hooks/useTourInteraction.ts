import { useEffect } from "react";
import { TOUR_RECIPE_ID, TOUR_STEP as S } from "../lib/tour";

const INTERACTIVE = 'a[href], button, input, textarea, select, summary, [contenteditable="true"], [tabindex]';

let advancing = false;
/** The guide may activate the app's real controls even while direct clicks are locked. */
export function performTourAction(action: () => void): void {
  const previous = advancing;
  advancing = true;
  try { action(); } finally { advancing = previous; }
}

/** Restricts mouse, keyboard and form actions to the current lesson. Restored on pause or finish. */
export function useTourInteraction(step: number, workspaceId?: string): void {
  useEffect(() => {
    const selectors = ['[data-tour-surface]'];
    switch (step) {
      case S.NAME: selectors.push('#workspace-name'); break;
      case S.STORE: selectors.push('[data-tour="open-store"]'); break;
      case S.PRIVATE: selectors.push('[role="tab"][data-tab="private"]'); break;
      case S.PUBLIC: selectors.push('[role="tab"][data-tab="public"]'); break;
      case S.FIND_RECIPE: selectors.push('[aria-label="Search recipes"]', `[data-recipe="${TOUR_RECIPE_ID}"]`); break;
      case S.REVIEW: selectors.push('summary'); break;
      case S.APPLY: selectors.push('[data-tour="recipe-apply"]'); break;
      case S.PICK: selectors.push(`[data-workspace-choice="${workspaceId}"]`, '[data-tour="workspace-picker-confirm"]'); break;
      case S.CONFIRM_PUBLIC:
      case S.DESIGN: selectors.push('textarea[aria-label="Message"]', 'button[aria-label="Send"]'); break;
    }
    const allowed = selectors.join(',');
    const permits = (element: Element) => !!element.closest(allowed);
    const changed = new Map<HTMLElement, boolean>();
    const fields = new Map<HTMLInputElement | HTMLTextAreaElement, boolean>();
    const restrict = () => {
      document.querySelectorAll<HTMLElement>(INTERACTIVE).forEach(element => {
        if ((element instanceof HTMLInputElement && ['text', 'search', 'password', 'email', 'url'].includes(element.type)) || element instanceof HTMLTextAreaElement) {
          if (!fields.has(element)) { fields.set(element, element.readOnly); element.readOnly = true; }
        }
        // Containers with a tab index must not make their permitted children inert.
        if (permits(element) || element.querySelector(allowed)) return;
        if (!changed.has(element)) { changed.set(element, element.inert); element.inert = true; }
      });
    };
    const block = (event: Event) => {
      if (advancing) return;
      const target = event.target instanceof Element ? event.target : null;
      if (!target) return;
      const label = target.closest('label');
      const labelled = label instanceof HTMLLabelElement ? label.control : null;
      const submitter = event instanceof SubmitEvent ? event.submitter : null;
      const keyboard = event instanceof KeyboardEvent ? event : null;
      const shortcut = keyboard && (keyboard.ctrlKey || keyboard.metaKey) && keyboard.key.toLowerCase() === 'k';
      const escape = keyboard?.key === 'Escape';
      const typing = target instanceof HTMLTextAreaElement || (target instanceof HTMLInputElement && fields.has(target));
      const editing = typing && (['beforeinput', 'paste', 'drop'].includes(event.type)
        || (keyboard && (keyboard.key === 'Enter' || keyboard.key === 'Backspace' || keyboard.key === 'Delete' || (keyboard.key.length === 1 && !keyboard.ctrlKey && !keyboard.metaKey))));
      const permitted = permits(target) || (!!labelled && permits(labelled))
        || (!!submitter && permits(submitter));
      if (shortcut || escape || editing || !permitted) {
        // Scrolling and selecting transcript text remain available.
        if (event.type === 'keydown' && !shortcut && !escape && !target.closest(INTERACTIVE)) return;
        event.preventDefault(); event.stopImmediatePropagation();
      }
    };
    restrict();
    const observer = new MutationObserver(restrict);
    observer.observe(document.body, { childList: true, subtree: true });
    const events = ['click', 'auxclick', 'submit', 'keydown', 'beforeinput', 'paste', 'drop'];
    events.forEach(type => document.addEventListener(type, block, true));
    return () => {
      observer.disconnect();
      events.forEach(type => document.removeEventListener(type, block, true));
      changed.forEach((inert, element) => { element.inert = inert; });
      fields.forEach((readOnly, element) => { element.readOnly = readOnly; });
    };
  }, [step, workspaceId]);
}

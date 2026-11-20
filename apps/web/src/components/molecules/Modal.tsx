import { type KeyboardEvent as ReactKeyboardEvent, type ReactNode, useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { IconButton } from "../atoms";

interface Props {
  readonly title: string;
  /** One line under the title saying what the dialog is for. */
  readonly description?: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
  /** Keep nested overlays inside their parent's outside-click boundary. */
  readonly portal?: boolean;
  /** Room for an editor rather than a few fields. */
  readonly wide?: boolean;
}

/**
 * A bottom sheet on mobile and centred dialog on desktop; Escape closes it, as does a click on the
 * backdrop. Children are usually a ModalBody then a ModalFooter, inside one form.
 */
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [contenteditable="true"], [tabindex]:not([tabindex="-1"])';

export function Modal({ title, description, onClose, children, portal = true, wide = false }: Props) {
  const descriptionId = useId();
  const dialog = useRef<HTMLDialogElement>(null);

  // Focus moves into the dialog unless a field inside already took it, and goes back to whatever
  // opened it on close, so a keyboard user is never left on a control that has disappeared.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = dialog.current;
    if (node && !node.contains(document.activeElement)) {
      node.focus({ preventScroll: true });
    }
    return () => {
      // An action may have moved focus on purpose, e.g. into a field it opened; leave it there.
      const lost = document.activeElement === document.body || node?.contains(document.activeElement);
      if (lost && opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, []);

  // Tab and Shift+Tab cycle inside the dialog rather than escaping to the page behind it.
  const trapFocus = (event: ReactKeyboardEvent<HTMLDialogElement>) => {
    if (document.documentElement.dataset.guidedTour === "true" || event.key !== "Tab" || event.defaultPrevented || !dialog.current) return;
    const focusable = [...dialog.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((element) => !element.closest("[hidden]"));
    const first = focusable.at(0);
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const content = (
    <div className="fixed inset-x-0 z-40 flex items-end justify-center pt-3 sm:items-center sm:p-4"
      style={{ top: "var(--tour-header, 0px)", bottom: "var(--tour-footer, 0px)" }}>
      <button
        type="button"
        aria-label="Dismiss dialog"
        tabIndex={-1}
        onClick={onClose}
        className="absolute inset-0 animate-fade-in cursor-default bg-zinc-950/60 backdrop-blur-sm"
      />
      {/* The browser styles <dialog> with its own colours, so both are set explicitly. */}
      <dialog
        ref={dialog}
        style={{ maxHeight: "calc(100dvh - var(--tour-header, 0px) - var(--tour-footer, 0px) - 1rem)" }}
        open
        tabIndex={-1}
        aria-modal="true"
        onKeyDown={trapFocus}
        aria-label={title}
        aria-describedby={description ? descriptionId : undefined}
        className={`relative m-0 flex max-h-[calc(100dvh-0.75rem)] w-full max-w-none animate-sheet-in flex-col overflow-hidden rounded-t-2xl border border-zinc-200 bg-white p-0 pb-[env(safe-area-inset-bottom)] text-zinc-900 shadow-2xl outline-none sm:max-h-[calc(100dvh-2rem)] sm:animate-pop-in ${wide ? "sm:max-w-3xl" : "sm:max-w-md"} sm:rounded-2xl sm:pb-0 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100`}
      >
        {/* The grab handle tells a phone user the sheet can be dismissed; it is not interactive. */}
        <span aria-hidden className="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-zinc-300 sm:hidden dark:bg-zinc-700" />
        {/* The rule matches the footer's, so content scrolled under the description has an edge. */}
        <header className="flex shrink-0 items-start justify-between gap-3 border-b border-zinc-200 px-4 pt-3 pb-4 sm:px-6 sm:pt-6 dark:border-zinc-800">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold tracking-tight [overflow-wrap:anywhere]">{title}</h2>
            {description && (
              <p id={descriptionId} className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
                {description}
              </p>
            )}
          </div>
          <IconButton label="Close" icon={X} onClick={onClose} className="-mt-1 -mr-2 size-10 shrink-0" />
        </header>
        {children}
      </dialog>
    </div>
  );
  return portal ? createPortal(content, document.body) : content;
}

export function ModalBody({ children }: { readonly children: ReactNode }) {
  return <div className="flex min-h-0 flex-col gap-5 overflow-y-auto overscroll-contain px-4 py-5 sm:px-6">{children}</div>;
}

/** The action row, set apart from the fields so the primary action is easy to find. */
export function ModalFooter({ children }: { readonly children: ReactNode }) {
  return (
    <div className="flex shrink-0 flex-wrap justify-end gap-2 border-t border-zinc-200 bg-zinc-50/80 px-4 py-3 max-sm:[&>button]:flex-1 sm:px-6 sm:py-4 dark:border-zinc-800 dark:bg-zinc-950/40">
      {children}
    </div>
  );
}

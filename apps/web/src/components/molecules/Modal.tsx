import { type ReactNode, useEffect, useId } from "react";
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
}

/**
 * A bottom sheet on mobile and centred dialog on desktop; Escape closes it, as does a click on the
 * backdrop. Children are usually a ModalBody then a ModalFooter, inside one form.
 */
export function Modal({ title, description, onClose, children, portal = true }: Props) {
  const descriptionId = useId();

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
    <div className="fixed inset-0 z-40 flex items-end justify-center pt-3 sm:items-center sm:p-4">
      <button
        type="button"
        aria-label="Dismiss dialog"
        tabIndex={-1}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-zinc-950/70 backdrop-blur-sm"
      />
      {/* The browser styles <dialog> with its own colours, so both are set explicitly. */}
      <dialog
        open
        aria-label={title}
        aria-describedby={description ? descriptionId : undefined}
        className="relative m-0 flex max-h-[calc(100dvh-0.75rem)] w-full max-w-none flex-col overflow-hidden rounded-t-2xl border border-zinc-200 bg-white p-0 pb-[env(safe-area-inset-bottom)] text-zinc-900 shadow-2xl sm:max-h-[calc(100dvh-2rem)] sm:max-w-md sm:rounded-xl sm:pb-0 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100"
      >
        <header className="flex shrink-0 items-start justify-between gap-3 px-4 pt-4 sm:px-6 sm:pt-6">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold [overflow-wrap:anywhere]">{title}</h2>
            {description && (
              <p id={descriptionId} className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
                {description}
              </p>
            )}
          </div>
          <IconButton label="Close" icon={X} onClick={onClose} className="flex size-11 shrink-0 items-center justify-center" />
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
    <div className="flex shrink-0 flex-wrap justify-end gap-2 border-t border-zinc-200 bg-zinc-50 px-4 py-4 sm:px-6 dark:border-zinc-800 dark:bg-zinc-950/40">
      {children}
    </div>
  );
}

import { type ReactNode, useEffect, useId } from "react";

interface Props {
  readonly title: string;
  /** One line under the title saying what the dialog is for. */
  readonly description?: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
}

/**
 * A centred dialog over a dimmed, blurred page; Escape closes it, as does a click on the
 * backdrop. Children are usually a ModalBody then a ModalFooter, inside one form.
 */
export function Modal({ title, description, onClose, children }: Props) {
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

  return (
    <div className="fixed inset-0 z-20 flex items-center justify-center p-4">
      <button
        type="button"
        aria-label="Close"
        tabIndex={-1}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-zinc-950/70 backdrop-blur-sm"
      />
      {/* The browser styles <dialog> with its own colours, so both are set explicitly. */}
      <dialog
        open
        aria-label={title}
        aria-describedby={description ? descriptionId : undefined}
        className="relative m-0 w-full max-w-md overflow-hidden rounded-xl border border-zinc-200 bg-white p-0 text-zinc-900 shadow-2xl dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100"
      >
        <header className="px-6 pt-6">
          <h2 className="text-lg font-semibold">{title}</h2>
          {description && (
            <p id={descriptionId} className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              {description}
            </p>
          )}
        </header>
        {children}
      </dialog>
    </div>
  );
}

export function ModalBody({ children }: { readonly children: ReactNode }) {
  return <div className="flex flex-col gap-5 px-6 py-5">{children}</div>;
}

/** The action row, set apart from the fields so the primary action is easy to find. */
export function ModalFooter({ children }: { readonly children: ReactNode }) {
  return (
    <div className="flex justify-end gap-2 border-t border-zinc-200 bg-zinc-50 px-6 py-4 dark:border-zinc-800 dark:bg-zinc-950/40">
      {children}
    </div>
  );
}

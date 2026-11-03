import { type ReactNode, useEffect } from "react";

interface Props {
  readonly title: string;
  readonly onClose: () => void;
  readonly children: ReactNode;
}

/** A centred dialog over a dimmed page; Escape closes it, as does a click on the backdrop. */
export function Modal({ title, onClose, children }: Props) {
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
    <div className="fixed inset-0 z-20 flex items-center justify-center">
      <button
        type="button"
        aria-label="Close"
        tabIndex={-1}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-black/50"
      />
      <dialog
        open
        aria-label={title}
        className="relative m-0 w-96 rounded-lg border border-zinc-200 bg-white p-5 shadow-xl dark:border-zinc-700 dark:bg-zinc-900"
      >
        <h2 className="mb-4 text-lg font-semibold">{title}</h2>
        {children}
      </dialog>
    </div>
  );
}

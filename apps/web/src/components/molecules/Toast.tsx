import { CircleCheck, X } from "lucide-react";
import { useEffect, useState } from "react";

// Long enough to read a sentence twice; the message stays in the live region after it fades.
const VISIBLE_MS = 6000;

/**
 * A page's latest outcome, floating above the content so it is seen wherever the page is
 * scrolled. The live region is always present, which is what makes readers announce it.
 */
export function Toast({ message }: { readonly message: string }) {
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    setHidden(false);
    if (!message) return undefined;
    const timer = setTimeout(() => setHidden(true), VISIBLE_MS);
    return () => clearTimeout(timer);
  }, [message]);

  const shown = message !== "" && !hidden;
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-0 z-40 flex justify-center px-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
      <div className={shown ? "pointer-events-auto flex max-w-md animate-sheet-in items-start gap-3 rounded-xl bg-zinc-900 py-3 pr-2 pl-4 text-sm text-white shadow-xl ring-1 ring-black/5 dark:bg-white dark:text-zinc-900" : "sr-only"}>
        {shown && <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-emerald-400 dark:text-emerald-600" />}
        <p role="status" className="min-w-0 flex-1 py-px">{message}</p>
        {shown && (
          <button type="button" aria-label="Dismiss message" onClick={() => setHidden(true)}
            className="-my-1 inline-flex size-7 shrink-0 items-center justify-center rounded-md text-white/60 hover:bg-white/10 hover:text-white dark:text-zinc-500 dark:hover:bg-zinc-100 dark:hover:text-zinc-900">
            <X aria-hidden className="size-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

import { CircleAlert, X } from "lucide-react";
import { IconButton } from "../atoms";

interface Props {
  readonly message: string;
  readonly onDismiss: () => void;
}

export function ErrorBanner({ message, onDismiss }: Props) {
  return (
    <div
      role="alert"
      className="m-4 mb-0 flex animate-fade-in items-start gap-2.5 rounded-lg border border-rose-200 bg-rose-50 py-2 pr-1.5 pl-3 text-sm text-rose-700 dark:border-rose-900/70 dark:bg-rose-950/50 dark:text-rose-300"
    >
      <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span className="min-w-0 flex-1 py-px [overflow-wrap:anywhere]">{message}</span>
      <IconButton label="Dismiss" icon={X} onClick={onDismiss} className="-my-0.5 shrink-0 text-current" />
    </div>
  );
}

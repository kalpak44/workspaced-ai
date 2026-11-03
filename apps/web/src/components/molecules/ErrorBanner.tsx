import { X } from "lucide-react";
import { IconButton } from "../atoms";

interface Props {
  readonly message: string;
  readonly onDismiss: () => void;
}

export function ErrorBanner({ message, onDismiss }: Props) {
  return (
    <div
      role="alert"
      className="m-4 mb-0 flex items-center justify-between rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950 dark:text-rose-300"
    >
      {message}
      <IconButton label="Dismiss" icon={X} onClick={onDismiss} className="text-current" />
    </div>
  );
}

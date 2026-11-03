import { Plus } from "lucide-react";
import { Button, Spinner } from "../atoms";

interface Props {
  readonly title: string;
  readonly hint: string;
  readonly busy?: boolean;
  readonly action?: { readonly label: string; readonly onClick: () => void };
}

export function EmptyState({ title, hint, busy = false, action }: Props) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-2 text-center">
      {busy && <Spinner className="mb-2 size-8 text-zinc-400" />}
      <h3 className="text-2xl font-semibold">{title}</h3>
      <p className="text-sm text-zinc-500 dark:text-zinc-400">{hint}</p>
      {action && (
        <div className="mt-6">
          <Button label={action.label} icon={Plus} onClick={action.onClick} />
        </div>
      )}
    </div>
  );
}

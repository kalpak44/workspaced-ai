import { Lock, Trash2 } from "lucide-react";
import type { Secret } from "../../hooks/useSecrets";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly secret: Secret;
  readonly removing: boolean;
  readonly onRemove: () => void;
}

/** One secret by name; its value is never shown, since the API never returns it. */
export function SecretItem({ secret, removing, onRemove }: Props) {
  let action;
  if (secret.managed) {
    action = (
      <span className="flex items-center gap-1 text-xs text-zinc-500 dark:text-zinc-400">
        <Lock aria-hidden className="size-3.5" />
        Model provider
      </span>
    );
  } else if (removing) {
    action = <Spinner label={`Removing ${secret.name}`} className="size-4 text-zinc-400" />;
  } else {
    action = <IconButton label={`Remove ${secret.name}`} icon={Trash2} onClick={onRemove} />;
  }
  return (
    <li className="flex items-center justify-between px-3 py-2">
      <span className="truncate font-mono text-sm">{secret.name}</span>
      {action}
    </li>
  );
}

import { Shield, ShieldOff, Trash2 } from "lucide-react";
import type { User } from "../../hooks/useUsers";
import { IconButton, Spinner } from "../atoms";

interface Props {
  readonly user: User;
  /** The signed-in user, who cannot delete themselves. */
  readonly self: boolean;
  readonly removing: boolean;
  readonly onRemove: () => void;
  /** Makes an admin a user, or a user an admin. */
  readonly onToggleAdmin: () => void;
}

/** One user by name and username. */
export function UserItem({ user, self, removing, onRemove, onToggleAdmin }: Props) {
  const name = `${user.firstName} ${user.lastName}`.trim();
  let action;
  if (self) {
    action = <span className="text-xs text-zinc-500 dark:text-zinc-400">You</span>;
  } else if (removing) {
    action = <Spinner label={`Deleting ${user.username}`} className="size-4 text-zinc-400" />;
  } else {
    const admin = user.role === "ADMIN";
    action = (
      <div className="flex shrink-0 items-center gap-1">
        <IconButton label={admin ? `Make ${user.username} a user` : `Make ${user.username} an admin`} icon={admin ? ShieldOff : Shield} onClick={onToggleAdmin} />
        <IconButton label={`Delete ${user.username}`} icon={Trash2} onClick={onRemove} />
      </div>
    );
  }
  return (
    <li className="flex min-w-0 items-center justify-between gap-3 px-3 py-2">
      <div className="flex min-w-0 items-center gap-3">
        {user.avatar ? (
          <img src={user.avatar} alt="" className="size-8 shrink-0 rounded-full object-cover" />
        ) : (
          <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-xs font-medium text-zinc-600 uppercase dark:bg-zinc-800 dark:text-zinc-300">
            {(user.firstName[0] ?? user.username[0]) + (user.lastName[0] ?? "")}
          </span>
        )}
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{name || user.username}</p>
          {(name || user.role === "ADMIN") && <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">{[name && user.username, user.role === "ADMIN" && "Admin"].filter(Boolean).join(" · ")}</p>}
        </div>
      </div>
      {action}
    </li>
  );
}

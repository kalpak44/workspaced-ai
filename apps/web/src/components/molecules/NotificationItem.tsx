import { ArrowRight, Circle, CircleCheck, Info, type LucideIcon, Server, Store, Wallet } from "lucide-react";
import type { Notification, NotificationKind, NotificationTone } from "../../hooks/useNotifications";
import { timeAgo } from "../../lib/time";

export const NOTIFICATION_ICONS: Record<NotificationKind, LucideIcon> = {
  billing: Wallet,
  workspace: Server,
  recipe: Store,
  system: Info,
};

/** The icon's colours; a reader scanning the list finds what needs them by colour first. */
export const NOTIFICATION_TONES: Record<NotificationTone, string> = {
  info: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300",
  success: "bg-emerald-50 text-emerald-600 dark:bg-emerald-950/60 dark:text-emerald-400",
  warning: "bg-amber-50 text-amber-600 dark:bg-amber-950/60 dark:text-amber-400",
  error: "bg-rose-50 text-rose-600 dark:bg-rose-950/60 dark:text-rose-400",
};

const FULL_DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" });

interface Props {
  readonly notification: Notification;
  readonly onOpen: () => void;
  readonly onToggleRead: () => void;
  /** Shows the notification's action as a link under it; the menu leaves it out to stay short. */
  readonly onAction?: () => void;
  /** menuitem inside the bell's menu; plain buttons in the full list. */
  readonly role?: "menuitem";
}

/**
 * One notification as a row. The whole row opens it; the read toggle and the action are
 * separate buttons beside it, since a button cannot hold another.
 */
export function NotificationItem({ notification, onOpen, onToggleRead, onAction, role }: Props) {
  const Icon = NOTIFICATION_ICONS[notification.kind];
  const { read, title, summary, createdAt, action } = notification;
  return (
    <div className={`group relative flex items-start gap-3 px-4 py-3 hover:bg-zinc-50 dark:hover:bg-zinc-800/60 ${read ? "" : "bg-sky-50/50 dark:bg-sky-950/20"}`}>
      <span className={`flex size-9 shrink-0 items-center justify-center rounded-full ${NOTIFICATION_TONES[notification.tone]}`}>
        <Icon aria-hidden className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        {/* Stretched over the row, so a click anywhere but the other buttons opens it. */}
        <button type="button" role={role} onClick={onOpen} className="block w-full text-left after:absolute after:inset-0 focus-visible:outline-none">
          <span className={`block truncate text-sm ${read ? "text-zinc-700 dark:text-zinc-300" : "font-semibold"}`}>{title}</span>
          <span className="line-clamp-2 text-xs text-zinc-500 dark:text-zinc-400">{summary}</span>
        </button>
        <div className="mt-1 flex items-center gap-3 text-xs">
          <time dateTime={createdAt} title={FULL_DATE.format(new Date(createdAt))} className="text-zinc-400 dark:text-zinc-500">{timeAgo(createdAt)}</time>
          {onAction && action && (
            <button type="button" onClick={onAction} className="relative inline-flex items-center gap-1 font-medium text-zinc-700 hover:text-zinc-900 hover:underline dark:text-zinc-300 dark:hover:text-white">
              {action.label}<ArrowRight aria-hidden className="size-3" />
            </button>
          )}
        </div>
      </div>
      <button type="button" role={role} onClick={onToggleRead} aria-label={read ? `Mark “${title}” as unread` : `Mark “${title}” as read`}
        title={read ? "Mark as unread" : "Mark as read"}
        // A read row keeps its toggle out of sight until hovered or focused; touch has no hover.
        className={`relative -mr-1 flex size-6 shrink-0 items-center justify-center rounded-md text-sky-500 hover:bg-zinc-200/70 dark:hover:bg-zinc-700 ${read ? "opacity-0 group-hover:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100" : ""}`}>
        {read
          ? <CircleCheck aria-hidden className="size-4 text-zinc-400" />
          : <Circle aria-hidden className="size-3 fill-current" />}
      </button>
    </div>
  );
}

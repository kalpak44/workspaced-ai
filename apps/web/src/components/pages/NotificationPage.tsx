import { ArrowRight, ChevronLeft, ChevronRight, MailOpen } from "lucide-react";
import { useEffect, useRef } from "react";
import { useNotifications } from "../../hooks/useNotifications";
import { navigate } from "../../hooks/useRoute";
import { Button, IconButton } from "../atoms";
import { EmptyState, notificationIcon, NOTIFICATION_TONES, PageHeader } from "../molecules";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "long", timeStyle: "short" });
const ACCENT = {
  info: "bg-zinc-400 dark:bg-zinc-600",
  success: "bg-emerald-500",
  warning: "bg-amber-500",
  error: "bg-rose-500",
};
const KIND_LABEL = { billing: "Billing", workspace: "Workspace", domain: "Domain", recipe: "Recipe", system: "System" };

/** One notification in full, with the action it suggests. Opening it marks it read. */
export function NotificationPage({ notificationId }: { readonly notificationId: string }) {
  const { notifications, status, markRead, markUnread } = useNotifications();
  const index = notifications.findIndex((n) => n.id === notificationId);
  const notification = notifications[index];
  const newer = index > 0 ? notifications[index - 1] : undefined;
  const older = index >= 0 ? notifications[index + 1] : undefined;
  const found = notification !== undefined;

  // A link opened directly reaches here without the menu, so the page marks it read itself —
  // once, so "Mark as unread" on this page is not undone straight away.
  const marked = useRef(false);
  useEffect(() => {
    if (found && !marked.current) {
      marked.current = true;
      void markRead(notificationId);
    }
  }, [found, notificationId, markRead]);

  const crumbs = [{ label: "Notifications", onClick: () => navigate({ name: "notifications" }) }];
  if (!notification) {
    return (
      <>
        <PageHeader variant="bar" title="Notification" crumbs={crumbs} />
        {status === "loading"
          ? <EmptyState busy title="Loading notification…" hint="Fetching it" />
          : <EmptyState title="Notification not found" hint="It may have been removed." />}
      </>
    );
  }

  const Icon = notificationIcon(notification);
  return (
    <>
      <PageHeader variant="bar" title={notification.title} crumbs={crumbs} actions={<>
        {notification.read && <Button label="Mark as unread" icon={MailOpen} variant="secondary" onClick={() => void markUnread(notification.id)} />}
        <IconButton label="Newer notification" icon={ChevronLeft} disabled={!newer} onClick={() => newer && navigate({ name: "notification", notificationId: newer.id })} />
        <IconButton label="Older notification" icon={ChevronRight} disabled={!older} onClick={() => older && navigate({ name: "notification", notificationId: older.id })} />
      </>} />
      <div className="mx-auto w-full max-w-3xl px-4 py-6 sm:px-6 sm:py-10">
        <article aria-labelledby="notification-title" className="card relative overflow-hidden">
          <div aria-hidden className={`absolute inset-x-0 top-0 h-1 ${ACCENT[notification.tone]}`} />
          <div className="p-5 sm:p-8">
            <div className="mb-6 flex items-center gap-3">
              <span className={`flex size-11 shrink-0 items-center justify-center rounded-xl ${NOTIFICATION_TONES[notification.tone]}`}>
                <Icon aria-hidden className="size-5" />
              </span>
              <div className="min-w-0 space-y-1">
                <p className="text-xs font-semibold tracking-wide text-zinc-600 uppercase dark:text-zinc-300">{KIND_LABEL[notification.kind]}</p>
                <time dateTime={notification.createdAt} className="block text-xs text-zinc-500 dark:text-zinc-400">{DATE.format(new Date(notification.createdAt))}</time>
              </div>
            </div>
            <h2 id="notification-title" className="text-xl leading-snug font-semibold tracking-tight text-pretty sm:text-2xl">{notification.title}</h2>
            <div className="mt-5 rounded-xl border border-zinc-200/80 bg-zinc-50 p-4 sm:p-5 dark:border-zinc-800 dark:bg-zinc-950/60">
              <p className="text-sm leading-7 whitespace-pre-wrap text-zinc-700 [overflow-wrap:anywhere] dark:text-zinc-300">{notification.body}</p>
            </div>
          </div>
          {notification.action && (
            <footer className="flex justify-end border-t border-zinc-200 bg-zinc-50/60 px-5 py-4 sm:px-8 dark:border-zinc-800 dark:bg-zinc-950/30">
              <Button label={notification.action.label} icon={ArrowRight} onClick={() => navigate(notification.action!.route)} />
            </footer>
          )}
        </article>
      </div>
    </>
  );
}

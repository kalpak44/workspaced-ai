import { ArrowRight, ChevronLeft, ChevronRight, MailOpen } from "lucide-react";
import { useEffect, useRef } from "react";
import { useNotifications } from "../../hooks/useNotifications";
import { navigate } from "../../hooks/useRoute";
import { Button, IconButton } from "../atoms";
import { EmptyState, notificationIcon, NOTIFICATION_TONES, PageHeader } from "../molecules";

const DATE = new Intl.DateTimeFormat("en-US", { dateStyle: "long", timeStyle: "short" });
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
      <article className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-4 py-6 sm:px-6 sm:py-8">
        <div className="flex items-center gap-3">
          <span className={`flex size-10 shrink-0 items-center justify-center rounded-full ${NOTIFICATION_TONES[notification.tone]}`}>
            <Icon aria-hidden className="size-5" />
          </span>
          <div className="min-w-0">
            <h2 className="text-xl font-semibold">{notification.title}</h2>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              {KIND_LABEL[notification.kind]} · <time dateTime={notification.createdAt}>{DATE.format(new Date(notification.createdAt))}</time>
            </p>
          </div>
        </div>
        <p className="text-sm leading-6 text-zinc-700 dark:text-zinc-300">{notification.body}</p>
        {notification.action && (
          <div><Button label={notification.action.label} icon={ArrowRight} onClick={() => navigate(notification.action!.route)} /></div>
        )}
      </article>
    </>
  );
}

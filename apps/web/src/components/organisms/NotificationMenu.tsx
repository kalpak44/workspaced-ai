import { Bell, BellOff } from "lucide-react";
import { useCallback, useRef, useState } from "react";
import { useDismiss } from "../../hooks/useDismiss";
import { useIsMobile } from "../../hooks/useIsMobile";
import { groupByDay, useNotifications } from "../../hooks/useNotifications";
import { navigate } from "../../hooks/useRoute";
import { Spinner } from "../atoms";
import { Menu, Modal, ModalBody, NotificationItem } from "../molecules";

// The menu shows the newest few; the rest are a link away, so it never needs to scroll.
const SHOWN = 5;

/** The bell: a badge with the unread count, and the newest notifications under it. */
export function NotificationMenu() {
  const { notifications, status, unread, markRead, markUnread, markAllRead } = useNotifications();
  const [open, setOpen] = useState(false);
  const mobile = useIsMobile();
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open && !mobile, close);
  const more = notifications.length - SHOWN;

  const items = <>
    <div className="flex items-center justify-between border-b border-zinc-200 px-4 py-2.5 dark:border-zinc-700">
      <span className="font-medium">
        Notifications
        {unread > 0 && <span className="ml-2 rounded-full bg-sky-100 px-1.5 py-0.5 text-xs font-medium text-sky-700 dark:bg-sky-950 dark:text-sky-300">{unread} new</span>}
      </span>
      {unread > 0 && (
        <button type="button" role="menuitem" onClick={() => void markAllRead()} className="text-xs font-medium text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100">
          Mark all read
        </button>
      )}
    </div>
    {status === "loading" && <div className="flex justify-center py-8"><Spinner className="size-5 text-zinc-400" /></div>}
    {status === "failed" && <p className="px-4 py-8 text-center text-zinc-500">Notifications could not be loaded.</p>}
    {status === "ready" && notifications.length === 0 && (
      <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
        <BellOff aria-hidden className="size-6 text-zinc-400" />
        <p className="font-medium">You're all caught up</p>
        <p className="text-xs text-zinc-500 dark:text-zinc-400">We'll let you know when something needs you.</p>
      </div>
    )}
    {status === "ready" && notifications.length > 0 && (
      <div className="max-h-[60dvh] overflow-y-auto">
        {groupByDay(notifications.slice(0, SHOWN)).map(([day, group]) => (
          <section key={day} aria-label={day}>
            <h3 className="bg-zinc-50 px-4 py-1 text-[11px] font-medium tracking-wide text-zinc-500 uppercase dark:bg-zinc-800/50 dark:text-zinc-400">{day}</h3>
            <div className="divide-y divide-zinc-100 dark:divide-zinc-800">
              {group.map((notification) => (
                <NotificationItem key={notification.id} role="menuitem" notification={notification}
                  onToggleRead={() => void (notification.read ? markUnread : markRead)(notification.id)}
                  onOpen={() => { close(); void markRead(notification.id); navigate({ name: "notification", notificationId: notification.id }); }} />
              ))}
            </div>
          </section>
        ))}
      </div>
    )}
    {status === "ready" && notifications.length > 0 && (
      <button type="button" role="menuitem" onClick={() => { close(); navigate({ name: "notifications" }); }}
        className="w-full border-t border-zinc-200 px-4 py-2.5 text-center text-sm font-medium hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800">
        {more > 0 ? `View all notifications (${more} more)` : "View all notifications"}
      </button>
    )}
  </>;

  return (
    <div ref={ref} className="relative">
      <button type="button" aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"} aria-expanded={open}
        aria-haspopup={mobile ? "dialog" : "menu"} onClick={() => setOpen(!open)}
        className="relative rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100">
        <Bell aria-hidden className="size-4" />
        {unread > 0 && (
          <span aria-hidden className="absolute -top-0.5 -right-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-semibold text-white">
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>
      {open && (mobile ? (
        <Modal title="Notifications" onClose={close}>
          <ModalBody><div role="menu" aria-label="Notifications" className="-mx-4 text-sm sm:-mx-6">{items}</div></ModalBody>
        </Modal>
      ) : (
        <Menu className="mt-2 w-96 rounded-lg shadow-xl">{items}</Menu>
      ))}
    </div>
  );
}

import { BellOff, CheckCheck } from "lucide-react";
import { useState } from "react";
import { groupByDay, type NotificationKind, useNotifications } from "../../hooks/useNotifications";
import { navigate } from "../../hooks/useRoute";
import { Button } from "../atoms";
import { EmptyState, NotificationItem, PageHeader, Tabs } from "../molecules";

const KINDS: ReadonlyArray<readonly [NotificationKind, string]> = [
  ["billing", "Billing"], ["workspace", "Workspaces"], ["domain", "Domains"], ["recipe", "Recipes"], ["system", "System"],
];

const CHIP = "shrink-0 rounded-full border px-3 py-1 text-xs font-medium transition-colors";
const CHIP_ON = "border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900";
const CHIP_OFF = "border-zinc-200 text-zinc-600 hover:border-zinc-400 dark:border-zinc-700 dark:text-zinc-300 dark:hover:border-zinc-500";

/** Every notification, newest first and grouped by day, filtered by read state and kind. */
export function NotificationsPage() {
  const { notifications, status, unread, markRead, markUnread, markAllRead } = useNotifications();
  const [filter, setFilter] = useState<"all" | "unread">("all");
  const [kind, setKind] = useState<NotificationKind | null>(null);
  const shown = notifications.filter((n) => (filter === "all" || !n.read) && (kind === null || n.kind === kind));

  return (
    <>
      <PageHeader title="Notifications"
        actions={unread > 0 && <Button label="Mark all read" icon={CheckCheck} variant="secondary" onClick={() => void markAllRead()} />} />
      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
        <div className="flex flex-col gap-3">
          <Tabs label="Show" value={filter} onChange={setFilter}
            tabs={[{ value: "all", label: "All" }, { value: "unread", label: unread > 0 ? `Unread (${unread})` : "Unread" }]} />
          <div role="group" aria-label="Kind" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 sm:mx-0 sm:flex-wrap sm:px-0">
            <button type="button" aria-pressed={kind === null} onClick={() => setKind(null)} className={`${CHIP} ${kind === null ? CHIP_ON : CHIP_OFF}`}>Everything</button>
            {KINDS.map(([value, label]) => (
              <button key={value} type="button" aria-pressed={kind === value} onClick={() => setKind(value)} className={`${CHIP} ${kind === value ? CHIP_ON : CHIP_OFF}`}>{label}</button>
            ))}
          </div>
        </div>
        {status === "loading" && <EmptyState busy title="Loading notifications…" hint="Fetching the latest" />}
        {status === "failed" && <EmptyState title="Notifications are unavailable" hint="They could not be loaded. Please try again later." />}
        {status === "ready" && shown.length === 0 && (
          <div className="flex flex-1 flex-col items-center justify-center gap-2 py-12 text-center">
            <BellOff aria-hidden className="size-8 text-zinc-400" />
            <h3 className="text-lg font-semibold">{filter === "unread" ? "You're all caught up" : "Nothing here"}</h3>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              {filter === "unread" || kind !== null ? "Try another filter to see older notifications." : "We'll let you know when something needs you."}
            </p>
          </div>
        )}
        {status === "ready" && groupByDay(shown).map(([day, group]) => (
          <section key={day} aria-label={day} className="flex flex-col gap-2">
            <h2 className="text-xs font-medium tracking-wide text-zinc-500 uppercase dark:text-zinc-400">{day}</h2>
            <ul aria-label={`${day} notifications`} className="divide-y divide-zinc-200 overflow-hidden rounded-xl border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {group.map((notification) => (
                <li key={notification.id}>
                  <NotificationItem notification={notification}
                    onToggleRead={() => void (notification.read ? markUnread : markRead)(notification.id)}
                    onAction={() => { void markRead(notification.id); if (notification.action) navigate(notification.action.route); }}
                    onOpen={() => { void markRead(notification.id); navigate({ name: "notification", notificationId: notification.id }); }} />
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </>
  );
}

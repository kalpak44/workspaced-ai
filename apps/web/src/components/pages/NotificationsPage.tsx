import { BellOff, CheckCheck } from "lucide-react";
import { useState } from "react";
import { groupByDay, type NotificationKind, useNotifications } from "../../hooks/useNotifications";
import { usePagination } from "../../hooks/usePagination";
import { navigate } from "../../hooks/useRoute";
import { Button } from "../atoms";
import { EmptyState, FilterChips, NotificationItem, PageHeader, Pagination, Tabs } from "../molecules";

const KINDS: ReadonlyArray<{ readonly value: NotificationKind; readonly label: string }> = [
  { value: "billing", label: "Billing" }, { value: "workspace", label: "Workspaces" }, { value: "domain", label: "Domains" },
  { value: "recipe", label: "Recipes" }, { value: "system", label: "System" },
];

const PAGE_SIZE = 20;

/** Every notification, newest first and grouped by day, filtered by read state and kind. */
export function NotificationsPage() {
  const { notifications, status, unread, markRead, markUnread, markAllRead } = useNotifications();
  const [filter, setFilter] = useState<"all" | "unread">("all");
  const [kind, setKind] = useState<NotificationKind | null>(null);
  const shown = notifications.filter((n) => (filter === "all" || !n.read) && (kind === null || n.kind === kind));
  const pages = usePagination(shown, PAGE_SIZE, `${filter}:${kind}`);

  return (
    <>
      <PageHeader title="Notifications"
        actions={unread > 0 && <Button label="Mark all read" icon={CheckCheck} variant="secondary" onClick={() => void markAllRead()} />} />
      <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-5 px-4 pt-6 pb-10 sm:px-6">
        <div className="flex flex-col gap-3">
          <Tabs label="Show" value={filter} onChange={setFilter}
            tabs={[{ value: "all", label: "All" }, { value: "unread", label: unread > 0 ? `Unread (${unread})` : "Unread" }]} />
          <FilterChips label="Kind" allLabel="Everything" options={KINDS} value={kind} onChange={setKind} />
        </div>
        {status === "loading" && <EmptyState busy title="Loading notifications…" hint="Fetching the latest" />}
        {status === "failed" && <EmptyState icon={BellOff} title="Notifications are unavailable" hint="They could not be loaded. Please try again later." />}
        {status === "ready" && shown.length === 0 && (
          <div className="card flex flex-1 flex-col">
            <EmptyState icon={BellOff} title={filter === "unread" ? "You're all caught up" : "Nothing here"}
              hint={filter === "unread" || kind !== null ? "Try another filter to see older notifications." : "We'll let you know when something needs you."}
              secondaryAction={filter === "unread" || kind !== null ? { label: "Show everything", onClick: () => { setFilter("all"); setKind(null); } } : undefined} />
          </div>
        )}
        {status === "ready" && groupByDay(pages.items).map(([day, group]) => (
          <section key={day} aria-label={day} className="flex flex-col gap-2">
            <h2 className="px-1 text-xs font-medium tracking-wide text-zinc-500 uppercase dark:text-zinc-400">{day}</h2>
            <ul aria-label={`${day} notifications`} className="card divide-y divide-zinc-200 overflow-hidden dark:divide-zinc-800">
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
        {status === "ready" && (
          <Pagination label="Notification pages" noun="notification" page={pages.page} pageCount={pages.pageCount} onChange={pages.setPage}
            range={{ first: pages.first, last: pages.last, total: pages.total }} />
        )}
      </div>
    </>
  );
}

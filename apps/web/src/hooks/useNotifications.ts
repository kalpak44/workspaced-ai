import { useEffect, useSyncExternalStore } from "react";
import * as api from "../api/notificationsApi";

export type { Notification, NotificationKind, NotificationTone } from "../api/notificationsApi";

type State = { readonly status: "loading" | "ready" | "failed"; readonly notifications: readonly api.Notification[] };

// One list for the whole page, so the bell's badge, its menu and the pages agree on what is read.
let state: State = { status: "loading", notifications: [] };
let loading: Promise<void> | null = null;
const listeners = new Set<() => void>();

const newestFirst = (list: readonly api.Notification[]) => [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

function set(next: State) {
  state = next;
  listeners.forEach((listener) => listener());
}

// New ones are sent by the API as things happen; the list is fetched again this often while shown.
const REFRESH_MS = 60_000;
let timer: ReturnType<typeof setInterval> | null = null;

function subscribe(listener: () => void) {
  listeners.add(listener);
  timer ??= setInterval(refresh, REFRESH_MS);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };
}

function load() {
  loading ??= api.listNotifications().then(
    (list) => set({ status: "ready", notifications: newestFirst(list) }),
    () => set({ status: "failed", notifications: [] }),
  );
}

// A failed refresh keeps the list it had; the next one tries again.
function refresh() {
  api.listNotifications().then(
    (list) => set({ status: "ready", notifications: newestFirst(list) }),
    () => {},
  );
}

async function setRead(ids: readonly string[], read: boolean) {
  if (ids.length === 0) return;
  set({ status: "ready", notifications: newestFirst(await api.setRead(ids, read)) });
}

const markRead = (id: string) => setRead([id], true);
const markUnread = (id: string) => setRead([id], false);

/** Forgets the list, so the next render loads it again; for tests. */
export function resetNotifications() {
  state = { status: "loading", notifications: [] };
  loading = null;
  if (timer !== null) clearInterval(timer);
  timer = null;
}

export type DayGroup = "Today" | "Yesterday" | "Earlier";

/** Splits a newest-first list into Today, Yesterday and Earlier, by the reader's own calendar. */
export function groupByDay(list: readonly api.Notification[], now = new Date()): Array<[DayGroup, api.Notification[]]> {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = today - 24 * 3600 * 1000;
  const groups = new Map<DayGroup, api.Notification[]>();
  for (const notification of list) {
    const at = Date.parse(notification.createdAt);
    const group = at >= today ? "Today" : at >= yesterday ? "Yesterday" : "Earlier";
    groups.set(group, [...(groups.get(group) ?? []), notification]);
  }
  return [...groups];
}

/** Every notification, newest first. */
export function useNotifications() {
  const current = useSyncExternalStore(subscribe, () => state);
  useEffect(load, []);
  return {
    ...current,
    unread: current.notifications.filter((n) => !n.read).length,
    markRead,
    markUnread,
    markAllRead: () => setRead(current.notifications.filter((n) => !n.read).map((n) => n.id), true),
  };
}

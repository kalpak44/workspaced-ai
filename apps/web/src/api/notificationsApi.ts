import { parseRoute, type Route } from "../lib/routes";
import { send } from "./http";

export type NotificationKind = "billing" | "workspace" | "recipe" | "system";
/** How much it needs the reader: an error asks them to act, info only tells them. */
export type NotificationTone = "info" | "success" | "warning" | "error";

export interface Notification {
  readonly id: string;
  readonly kind: NotificationKind;
  readonly tone: NotificationTone;
  readonly title: string;
  /** One line for the menu and the list. */
  readonly summary: string;
  /** The full text, shown on the notification's own page. */
  readonly body: string;
  readonly createdAt: string;
  readonly read: boolean;
  /** Where the notification asks the reader to go next. */
  readonly action?: { readonly label: string; readonly route: Route };
}

/** As the API sends it: enums in capitals, and the action as a path in this app. */
export interface ApiNotification extends Omit<Notification, "kind" | "tone" | "action"> {
  readonly kind: Uppercase<NotificationKind>;
  readonly tone: Uppercase<NotificationTone>;
  readonly action: { readonly label: string; readonly path: string } | null;
}

export const NOTIFICATIONS_URL = "/api/v1/notifications";

function fromApi({ kind, tone, action, ...rest }: ApiNotification): Notification {
  return {
    ...rest,
    kind: kind.toLowerCase() as NotificationKind,
    tone: tone.toLowerCase() as NotificationTone,
    ...(action ? { action: { label: action.label, route: parseRoute(action.path) } } : {}),
  };
}

/** The newest 200, newest first. */
export async function listNotifications(): Promise<Notification[]> {
  const list: ApiNotification[] = await (await send(NOTIFICATIONS_URL)).json();
  return list.map(fromApi);
}

/** Answers the list as it stands after the change. */
export async function setRead(ids: readonly string[], read: boolean): Promise<Notification[]> {
  const response = await send(`${NOTIFICATIONS_URL}/read`, { method: "PUT", body: JSON.stringify({ ids, read }) });
  const list: ApiNotification[] = await response.json();
  return list.map(fromApi);
}

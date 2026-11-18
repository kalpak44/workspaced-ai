import { send } from "./http";

const MAIL_URL = "/api/v1/mail";

/** Where mail is sent from. Only an admin may read it or send. */
export interface MailSettings {
  readonly domain: string;
  /** Where the platform's own notifications come from; a reply to it bounces. */
  readonly noReply: string;
  /** False until a Mailjet account is set up; sending then answers 503. */
  readonly configured: boolean;
}

/** `from` is the sender's address before the @; the API adds its own domain. */
export interface NewMail {
  readonly from: string;
  readonly fromName: string;
  readonly to: readonly string[];
  readonly replyTo: string | null;
  readonly subject: string;
  readonly text: string;
}

export async function getMailSettings(): Promise<MailSettings> {
  return (await send(MAIL_URL)).json();
}

/** Resolves once Mailjet accepted the message, with Mailjet's id for it. */
export async function sendMail(mail: NewMail): Promise<{ id: string }> {
  return (await send(`${MAIL_URL}/messages`, { method: "POST", body: JSON.stringify(mail) })).json();
}

import { send } from "./http";

const MAIL_URL = "/api/v1/mail";

/** An address mail can be sent from, and the template its messages go out in. */
export interface MailSender {
  /** The address before the @, which a message names as its sender. */
  readonly localPart: string;
  readonly address: string;
  /** Shown beside the address in the reader's inbox. */
  readonly name: string;
  /** Markdown added under every message. */
  readonly signature: string;
  /** One line of plain text at the top of the footer. */
  readonly footer: string;
}

export interface MailTemplate {
  readonly signature: string;
  readonly footer: string;
}

/** Where mail is sent from. Only an admin may read it or send. */
export interface MailSettings {
  readonly domain: string;
  /** Where the platform's own notifications come from; a reply to it bounces. */
  readonly noReply: string;
  /** False until a Mailjet account is set up; sending then answers 503. */
  readonly configured: boolean;
  readonly senders: readonly MailSender[];
}

/** `from` is one of the senders' local parts; `text` is Markdown. */
export interface NewMail {
  readonly from: string;
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

export async function saveMailTemplate(localPart: string, template: MailTemplate): Promise<MailSender> {
  return (await send(`${MAIL_URL}/senders/${encodeURIComponent(localPart)}`, { method: "PUT", body: JSON.stringify(template) })).json();
}

/** The message as its reader would see it, in `template` when given; nothing is sent. */
export async function previewMail(from: string, subject: string, text: string, template: MailTemplate): Promise<string> {
  const body = JSON.stringify({ from, subject, text, template });
  return ((await (await send(`${MAIL_URL}/preview`, { method: "POST", body })).json()) as { html: string }).html;
}

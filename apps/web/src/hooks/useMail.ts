import { useEffect, useState } from "react";
import { ApiError } from "../api/http";
import { getMailSettings, type MailSettings, type MailTemplate, type NewMail, previewMail, saveMailTemplate, sendMail } from "../api/mailApi";

export type { MailSender, MailSettings, MailTemplate, NewMail } from "../api/mailApi";

/** The reason a send failed, in words; the API's own detail when it is Mailjet's refusal. */
function reason(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 400) return "Check the addresses, subject and message, then try again.";
    if (error.status === 502 && error.detail) return error.detail;
    if (error.status === 503) return "Mail cannot be sent right now. Please try again later.";
  }
  return "Could not send the message. Please try again.";
}

/** The admin's mail settings: sending as one of the senders, and editing their templates. */
export function useMail() {
  const [settings, setSettings] = useState<MailSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [sending, setSending] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setError(null);
    getMailSettings()
      .then((value) => { if (current) setSettings(value); })
      .catch(() => { if (current) setError("Could not load mail settings."); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [attempt]);

  /** Resolves to null once Mailjet accepted it, or to why it was not sent. */
  async function send(mail: NewMail): Promise<string | null> {
    setSending(true);
    try {
      await sendMail(mail);
      return null;
    } catch (failure) {
      return reason(failure);
    } finally {
      setSending(false);
    }
  }

  /** Resolves to whether it was saved. */
  async function saveTemplate(localPart: string, template: MailTemplate): Promise<boolean> {
    setSaving(true);
    try {
      const saved = await saveMailTemplate(localPart, template);
      setSettings((current) => current && { ...current, senders: current.senders.map((s) => (s.localPart === saved.localPart ? saved : s)) });
      return true;
    } catch {
      return false;
    } finally {
      setSaving(false);
    }
  }

  /** The HTML a reader would see, or null when it could not be built. */
  async function preview(from: string, subject: string, text: string, template: MailTemplate): Promise<string | null> {
    try {
      return await previewMail(from, subject, text, template);
    } catch {
      return null;
    }
  }

  return { settings, loading, error, sending, saving, send, saveTemplate, preview, retry: () => setAttempt((value) => value + 1) };
}

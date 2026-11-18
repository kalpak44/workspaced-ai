import { Eye, Send } from "lucide-react";
import { useEffect, useState } from "react";
import { type MailSender, type MailTemplate, useMail } from "../../hooks/useMail";
import { Button, Select, Spinner, TextField } from "../atoms";
import { SettingsSection } from "../molecules";
import { InstructionsEditor } from "../molecules/InstructionsEditor";

const EMPTY = { to: "", replyTo: "", subject: "", text: "" };

/** Recipients as typed: separated by commas, semicolons or spaces. */
function recipients(value: string): string[] {
  return value.split(/[\s,;]+/).filter(Boolean);
}

/**
 * Writes a message in Markdown and sends it as one of the senders, inside the site's header and
 * footer and that sender's own template, which can be edited beside it.
 */
export function MailPanel() {
  const { settings, loading, error, sending, saving, send, saveTemplate, preview, retry } = useMail();
  const [from, setFrom] = useState("");
  const [draft, setDraft] = useState(EMPTY);
  const [template, setTemplate] = useState<MailTemplate>({ signature: "", footer: "" });
  const [failure, setFailure] = useState("");
  const [notice, setNotice] = useState("");
  const [templateNotice, setTemplateNotice] = useState("");
  const [shown, setShown] = useState<string | null>(null);
  const sender: MailSender | undefined = settings?.senders.find((s) => s.localPart === from) ?? settings?.senders[0];

  // The template editor follows the chosen sender, starting from what is saved for it.
  useEffect(() => {
    if (sender) setTemplate({ signature: sender.signature, footer: sender.footer });
  }, [sender]);

  const to = recipients(draft.to);
  const ready = sender !== undefined && to.length > 0 && draft.subject.trim() !== "" && draft.text.trim() !== "";
  const templateChanged = sender !== undefined && (template.signature !== sender.signature || template.footer !== sender.footer);
  const edit = (field: keyof typeof EMPTY) => (event: { target: { value: string } }) => {
    setDraft({ ...draft, [field]: event.target.value });
    setNotice("");
  };

  async function submit() {
    if (!sender) return;
    setFailure("");
    setNotice("");
    const reason = await send({ from: sender.localPart, to, replyTo: draft.replyTo.trim() || null, subject: draft.subject.trim(), text: draft.text });
    if (reason) {
      setFailure(reason);
      return;
    }
    setDraft({ ...EMPTY, replyTo: draft.replyTo });
    setShown(null);
    setNotice(`Sent to ${to.join(", ")}.`);
  }

  async function show() {
    if (!sender) return;
    setFailure("");
    const html = await preview(sender.localPart, draft.subject.trim(), draft.text, template);
    if (html === null) setFailure("Could not build the preview. Please try again.");
    setShown(html);
  }

  async function storeTemplate() {
    if (!sender) return;
    setTemplateNotice(await saveTemplate(sender.localPart, template) ? "Template saved." : "Could not save the template. Please try again.");
  }

  if (loading) return <SettingsSection title="Mail" description="Loading…"><Spinner label="Loading mail settings" className="size-5 text-zinc-400" /></SettingsSection>;
  if (!settings || !sender) {
    return (
      <SettingsSection title="Mail" description="Send mail from the platform's own addresses.">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error ?? "No sender is set up."}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </SettingsSection>
    );
  }

  return (
    <div className="space-y-6">
      <SettingsSection title="Mail" description="Write only the message: it goes out inside the site's header and footer, with the chosen address's signature and footer line.">
        {!settings.configured && <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
          No mail account is set up for this installation, so nothing can be sent yet.
        </p>}
        <form aria-label="New message" className="flex flex-col gap-4" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <div className="flex min-w-0 flex-col gap-1.5 text-sm">
            <label htmlFor="mail-from" className="font-medium text-zinc-700 dark:text-zinc-300">From</label>
            <Select id="mail-from" value={sender.localPart} onChange={(event) => { setFrom(event.target.value); setShown(null); setNotice(""); setTemplateNotice(""); }}>
              {settings.senders.map((s) => <option key={s.localPart} value={s.localPart}>{s.address}</option>)}
            </Select>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField id="mail-to" label="To" required autoComplete="off" hint="Separate several addresses with commas." value={draft.to} onChange={edit("to")} />
            <TextField id="mail-reply-to" label="Reply to" type="email" maxLength={254} hint="Optional; replies go to the sender otherwise." value={draft.replyTo} onChange={edit("replyTo")} />
          </div>
          <TextField id="mail-subject" label="Subject" required maxLength={200} value={draft.subject} onChange={edit("subject")} />
          <div className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium text-zinc-700 dark:text-zinc-300">Message</span>
            <InstructionsEditor fileName="message.md" label="Message" value={draft.text}
              onChange={(text) => { setDraft((current) => ({ ...current, text })); setNotice(""); }} />
          </div>
          {failure && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{failure}</p>}
          {notice && <p className="text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>}
          <div className="flex justify-end gap-2">
            <Button label="Preview" icon={Eye} variant="secondary" onClick={() => void show()} />
            <Button label="Send" icon={Send} type="submit" busy={sending} disabled={!ready || !settings.configured} />
          </div>
        </form>
        {shown !== null && <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Preview</span>
            <Button label="Close preview" variant="secondary" onClick={() => setShown(null)} />
          </div>
          {/* Sandboxed with no permissions: the preview is shown, never run. */}
          <iframe title="Message preview" sandbox="" srcDoc={shown} className="h-[36rem] w-full rounded-lg border border-zinc-200 bg-zinc-50 dark:border-zinc-800" />
        </div>}
      </SettingsSection>

      <SettingsSection title={`Template for ${sender.address}`} description={`Every message from ${sender.address} ends with this signature and carries this footer line. The preview shows unsaved changes.`}>
        <form aria-label="Template" className="flex flex-col gap-4" onSubmit={(event) => { event.preventDefault(); void storeTemplate(); }}>
          <div className="flex flex-col gap-1.5 text-sm">
            <label htmlFor="mail-signature" className="font-medium text-zinc-700 dark:text-zinc-300">Signature</label>
            <textarea id="mail-signature" rows={3} maxLength={5000} aria-describedby="mail-signature-hint" value={template.signature}
              onChange={(event) => { setTemplate({ ...template, signature: event.target.value }); setTemplateNotice(""); }}
              className="field block w-full resize-y px-3 py-2" />
            <span id="mail-signature-hint" className="text-xs text-zinc-500 dark:text-zinc-400">Markdown, added under the message. Leave it empty for none.</span>
          </div>
          <TextField id="mail-footer" label="Footer line" maxLength={300} hint="Plain text at the top of the footer, e.g. how to reply." value={template.footer}
            onChange={(event) => { setTemplate({ ...template, footer: event.target.value }); setTemplateNotice(""); }} />
          {templateNotice && <p className="text-sm text-zinc-500 dark:text-zinc-400">{templateNotice}</p>}
          <div className="flex justify-end gap-2">
            {templateChanged && !saving && <Button label="Discard changes" variant="secondary" onClick={() => setTemplate({ signature: sender.signature, footer: sender.footer })} />}
            <Button label="Save template" type="submit" busy={saving} disabled={!templateChanged} />
          </div>
        </form>
      </SettingsSection>
    </div>
  );
}

import { Send } from "lucide-react";
import { useState } from "react";
import { useMail } from "../../hooks/useMail";
import { Button, Spinner, TextField } from "../atoms";
import { SettingsSection } from "../molecules";
import { InstructionsEditor } from "../molecules/InstructionsEditor";

const EMPTY = { from: "", fromName: "", to: "", replyTo: "", subject: "", text: "" };

/** Recipients as typed: separated by commas, semicolons or spaces. */
function recipients(value: string): string[] {
  return value.split(/[\s,;]+/).filter(Boolean);
}

/** Writes a message in Markdown and sends it, formatted, as any address on the platform's domain. */
export function MailPanel() {
  const { settings, loading, error, sending, send, retry } = useMail();
  const [draft, setDraft] = useState(EMPTY);
  const [failure, setFailure] = useState("");
  const [notice, setNotice] = useState("");
  const edit = (field: keyof typeof EMPTY) => (event: { target: { value: string } }) => {
    setDraft({ ...draft, [field]: event.target.value });
    setNotice("");
  };
  const to = recipients(draft.to);
  const ready = draft.from.trim() !== "" && to.length > 0 && draft.subject.trim() !== "" && draft.text.trim() !== "";

  async function submit() {
    setFailure("");
    setNotice("");
    const reason = await send({
      from: draft.from.trim(),
      fromName: draft.fromName.trim(),
      to,
      replyTo: draft.replyTo.trim() || null,
      subject: draft.subject.trim(),
      text: draft.text,
    });
    if (reason) {
      setFailure(reason);
      return;
    }
    // The sender is kept, so a second message from the same address needs only its content.
    setDraft({ ...EMPTY, from: draft.from, fromName: draft.fromName, replyTo: draft.replyTo });
    setNotice(`Sent to ${to.join(", ")}.`);
  }

  return (
    <SettingsSection title="Mail" description="Send mail as any address on the platform's domain. Replies arrive wherever that address forwards to.">
      {loading ? <Spinner label="Loading mail settings" className="size-5 text-zinc-400" /> : !settings ? <div className="space-y-3">
        <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>
        <Button label="Retry" variant="secondary" onClick={retry} />
      </div> : <form aria-label="New message" className="flex flex-col gap-4" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          Notifications from the platform itself come from <span className="font-medium text-zinc-700 dark:text-zinc-300">{settings.noReply}</span>, which takes no replies.
        </p>
        {!settings.configured && <p role="status" className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
          No mail account is set up for this installation, so nothing can be sent yet.
        </p>}
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-1.5 text-sm">
            <label htmlFor="mail-from" className="font-medium text-zinc-700 dark:text-zinc-300">From</label>
            <div className="flex min-w-0 items-center gap-2">
              <input id="mail-from" required maxLength={64} autoComplete="off" placeholder="support" value={draft.from} onChange={edit("from")}
                className="field min-h-9 w-full min-w-0 px-3 py-2" />
              <span className="shrink-0 text-zinc-500 dark:text-zinc-400">@{settings.domain}</span>
            </div>
          </div>
          <TextField id="mail-from-name" label="Sender name" maxLength={100} placeholder="workspaced support" value={draft.fromName} onChange={edit("fromName")} />
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
        {notice && <p role="status" className="text-sm text-zinc-500 dark:text-zinc-400">{notice}</p>}
        <div className="flex justify-end">
          <Button label="Send" icon={Send} type="submit" busy={sending} disabled={!ready || !settings.configured} />
        </div>
      </form>}
    </SettingsSection>
  );
}

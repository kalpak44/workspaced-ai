import { useState } from "react";
import { contentError, type DnsRecord, type DnsRecordInput, RECORD_NAME, RECORD_TYPES, type RecordType } from "../../hooks/useDnsRecords";
import { Button, TextField } from "../atoms";
import { Modal, ModalBody, ModalFooter } from "../molecules";

/** The TTLs offered, in seconds; 1 is the provider's automatic choice. */
export const TTLS: ReadonlyArray<readonly [number, string]> = [[1, "Auto"], [300, "5 minutes"], [3600, "1 hour"], [86400, "1 day"]];

const CONTENT_LABEL: Record<RecordType, string> = {
  A: "IPv4 address",
  AAAA: "IPv6 address",
  CNAME: "Target",
  TXT: "Content",
  MX: "Mail server",
};

const SELECT = "min-h-10 rounded-md border border-zinc-300 bg-transparent px-2 text-sm outline-none dark:border-zinc-700 dark:bg-zinc-900";

interface Props {
  readonly domain: string;
  /** The record being edited; absent to add one. */
  readonly record?: DnsRecord;
  readonly onSave: (record: DnsRecordInput) => Promise<void>;
  readonly onClose: () => void;
}

/** Adds or edits one record; validates the content for its type before saving. */
export function DnsRecordDialog({ domain, record, onSave, onClose }: Props) {
  const [type, setType] = useState<RecordType>(record?.type ?? "A");
  const [name, setName] = useState(record?.name ?? "");
  const [content, setContent] = useState(record?.content ?? "");
  const [ttl, setTtl] = useState(record?.ttl ?? 1);
  const [priority, setPriority] = useState(String(record?.priority ?? 10));
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);

  const normalisedName = name.trim().toLowerCase() || "@";
  const nameError = RECORD_NAME.test(normalisedName) ? null : "Use @, or a name like www or api.v2.";
  const valueError = contentError(type, content);
  const priorityError = type === "MX" && !/^\d{1,5}$/.test(priority) ? "Enter a number from 0 to 65535." : null;
  const invalid = Boolean(nameError || valueError || priorityError);

  async function submit() {
    setTouched(true);
    if (invalid) return;
    setSaving(true);
    try {
      await onSave({ type, name: normalisedName, content: content.trim(), ttl, ...(type === "MX" ? { priority: Number(priority) } : {}) });
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title={record ? "Edit record" : "Add record"} description={`A DNS record for ${domain}.`} onClose={onClose}>
      <form noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }} className="flex min-h-0 flex-col">
        <ModalBody>
          <div className="grid grid-cols-[auto_1fr] gap-3">
            <div className="flex flex-col gap-1.5 text-sm">
              <label htmlFor="record-type" className="font-medium text-zinc-700 dark:text-zinc-300">Type</label>
              <select id="record-type" value={type} onChange={(event) => setType(event.target.value as RecordType)} className={SELECT}>
                {RECORD_TYPES.map((option) => <option key={option}>{option}</option>)}
              </select>
            </div>
            <TextField id="record-name" label="Name" placeholder="@" value={name} hint={`@ is ${domain} itself.`}
              error={touched ? nameError : null} onChange={(event) => setName(event.target.value)} />
          </div>
          <TextField id="record-content" label={CONTENT_LABEL[type]} value={content}
            error={touched ? valueError : null} onChange={(event) => setContent(event.target.value)} />
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5 text-sm">
              <label htmlFor="record-ttl" className="font-medium text-zinc-700 dark:text-zinc-300">TTL</label>
              <select id="record-ttl" value={ttl} onChange={(event) => setTtl(Number(event.target.value))} className={SELECT}>
                {TTLS.map(([seconds, label]) => <option key={seconds} value={seconds}>{label}</option>)}
              </select>
            </div>
            {type === "MX" && (
              <TextField id="record-priority" label="Priority" inputMode="numeric" value={priority}
                error={touched ? priorityError : null} onChange={(event) => setPriority(event.target.value)} />
            )}
          </div>
        </ModalBody>
        <ModalFooter>
          <Button label="Cancel" variant="secondary" onClick={onClose} />
          <Button label={record ? "Save record" : "Add record"} type="submit" busy={saving} />
        </ModalFooter>
      </form>
    </Modal>
  );
}

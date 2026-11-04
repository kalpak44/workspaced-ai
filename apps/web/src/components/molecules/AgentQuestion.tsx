import { MessageCircleQuestion } from "lucide-react";
import { useId, useState } from "react";
import type { AgentForm, FormAnswer, FormField, FormValue } from "../../hooks/useChat";
import { Button } from "../atoms";

const inputClass = "w-full rounded-lg border border-zinc-300 bg-transparent px-3 py-2 text-sm outline-none focus:border-zinc-500 dark:border-zinc-700";

function Field({ field, value, onChange }: {
  readonly field: FormField;
  readonly value: FormValue | undefined;
  readonly onChange: (value: FormValue | undefined) => void;
}) {
  const id = useId();
  const label = field.title ?? field.key;
  const descriptionId = field.description ? `${id}-description` : undefined;
  let control;
  if (field.type === "external") {
    const safe = field.url && /^https?:\/\//i.test(field.url);
    control = safe ? <a href={field.url} target="_blank" rel="noopener noreferrer" className="text-sm underline">Open {label}</a>
      : <p className="text-sm text-zinc-500">This question requires an external response.</p>;
  } else if (field.type === "boolean") {
    control = <select id={id} required={field.required} value={value === undefined ? "" : String(value)}
      aria-describedby={descriptionId} onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value === "true")} className={inputClass}>
      <option value="">Choose an answer</option><option value="true">Yes</option><option value="false">No</option>
    </select>;
  } else if (field.type === "multiselect") {
    const selected = Array.isArray(value) ? value as readonly string[] : [];
    const options = field.options ?? [];
    const custom = selected.filter((item) => !options.some((option) => option.value === item));
    control = <div id={id} role="group" aria-label={label} aria-describedby={descriptionId} className="flex flex-col gap-2">
      {options.map((option) => <label key={option.value} className="flex items-start gap-2 text-sm">
        <input type="checkbox" checked={selected.includes(option.value)} className="mt-1 accent-zinc-700"
          onChange={(e) => onChange(e.target.checked ? [...selected, option.value] : selected.filter((item) => item !== option.value))} />
        <span>{option.label}{option.description && <span className="block text-xs text-zinc-500">{option.description}</span>}</span>
      </label>)}
      {field.custom && <textarea aria-label={`${label}: other answers`} placeholder="Other answers, one per line"
        value={custom.join("\n")} className={inputClass} onChange={(e) => onChange([
          ...selected.filter((item) => options.some((option) => option.value === item)),
          ...e.target.value.split("\n").filter(Boolean),
        ])} />}
    </div>;
  } else if (field.options?.length && !field.custom) {
    control = <select id={id} aria-describedby={descriptionId} required={field.required} value={typeof value === "string" ? value : ""}
      onChange={(e) => onChange(e.target.value || undefined)} className={inputClass}>
      <option value="">Choose an answer</option>
      {field.options.map((option) => <option key={option.value} value={option.value}>{option.label}{option.description ? ` — ${option.description}` : ""}</option>)}
    </select>;
  } else {
    const numeric = field.type === "number" || field.type === "integer";
    const type = numeric ? "number" : field.format === "uri" ? "url" : field.format === "date-time" ? "datetime-local" : field.format ?? "text";
    control = <>
      <input id={id} type={type} required={field.required} aria-describedby={descriptionId}
        value={typeof value === "string" || typeof value === "number" ? value : ""}
        min={field.minimum} max={field.maximum} step={field.type === "integer" ? 1 : "any"}
        minLength={field.minLength} maxLength={field.maxLength} pattern={field.pattern}
        placeholder={field.placeholder ?? "Your answer…"} list={field.options?.length ? `${id}-options` : undefined}
        onChange={(e) => onChange(e.target.value === "" ? undefined : numeric ? Number(e.target.value) : e.target.value)} className={inputClass} />
      {field.options?.length && <datalist id={`${id}-options`}>{field.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</datalist>}
    </>;
  }
  return <div className="flex flex-col gap-1.5">
    <label htmlFor={id} className="text-sm font-medium">{label}{field.required && <span className="ml-1 text-zinc-400">*</span>}</label>
    {field.description && <p id={descriptionId} className="text-xs text-zinc-500 dark:text-zinc-400">{field.description}</p>}
    {control}
  </div>;
}

/** Questions interrupt the running tool, so answers go to its form rather than a new prompt. */
export function AgentQuestion({ form, onAnswer }: {
  readonly form: AgentForm;
  readonly onAnswer: (id: string, answer?: FormAnswer) => Promise<boolean>;
}) {
  const headingId = useId();
  const [draft, setDraft] = useState<Record<string, FormValue>>(() => Object.fromEntries(
    form.fields.filter((field) => field.default !== undefined).map((field) => [field.key, field.default!]),
  ));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const active = form.fields.filter((field) => field.when?.every((condition) =>
    condition.op === "eq" ? draft[condition.key] === condition.value : draft[condition.key] !== condition.value,
  ) ?? true);
  const submit = async (cancel = false) => {
    if (saving) return;
    const invalid = active.find((field) => {
      if (field.type !== "multiselect") return false;
      const value = draft[field.key];
      const count = Array.isArray(value) ? value.length : 0;
      return count < Math.max(field.minItems ?? 0, field.required ? 1 : 0) ||
        (field.maxItems !== undefined && count > field.maxItems);
    });
    if (!cancel && invalid) { setError(`Check the number of answers for ${invalid.title ?? invalid.key}.`); return; }
    setSaving(true);
    setError(null);
    try {
      const answer = Object.fromEntries(active.filter((field) => draft[field.key] !== undefined && field.type !== "external").map((field) => [field.key, draft[field.key]]));
      if (!await onAnswer(form.id, cancel ? undefined : answer)) setError("Could not send your response. Please try again.");
    } catch {
      setError("Could not send your response. Please try again.");
    } finally { setSaving(false); }
  };
  return <form aria-labelledby={headingId} onSubmit={(event) => { event.preventDefault(); void submit(); }}
    className="rounded-xl border border-zinc-300 bg-zinc-50 p-4 dark:border-zinc-700 dark:bg-zinc-900">
    <div className="mb-4 flex items-start gap-2">
      <MessageCircleQuestion aria-hidden className="mt-0.5 size-4 shrink-0 text-zinc-500" />
      <div><p className="mb-1 text-xs text-zinc-500">Waiting for your answer</p><h3 id={headingId} className="text-sm font-semibold">{form.title}</h3></div>
    </div>
    <fieldset disabled={saving} className="flex min-w-0 flex-col gap-4">
      {active.filter((field) => !field.hidden).map((field) => <Field key={field.key} field={field} value={draft[field.key]}
        onChange={(value) => setDraft((current) => { const next = { ...current }; if (value === undefined) delete next[field.key]; else next[field.key] = value; return next; })} />)}
    </fieldset>
    {error && <p role="alert" className="mt-3 text-xs text-rose-600">{error}</p>}
    <div className="mt-4 flex justify-end gap-2">
      <Button label="Dismiss question" variant="secondary" disabled={saving} onClick={() => void submit(true)} />
      <Button label="Submit answer" type="submit" busy={saving} />
    </div>
  </form>;
}

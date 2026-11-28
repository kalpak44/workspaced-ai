/** Short acknowledgement of Terms sections 5, 6 and 9 and the Privacy policy's AI-provider disclosure. */
export function AiProviderNotice({ accepted, onChange, disabled = false }: { readonly accepted: boolean; readonly onChange: (accepted: boolean) => void; readonly disabled?: boolean }) {
  const link = "font-medium underline underline-offset-4";
  return <div className="space-y-3 rounded-xl border border-zinc-200 bg-zinc-50 p-4 text-sm leading-6 text-zinc-600 dark:border-zinc-800 dark:bg-zinc-900/60 dark:text-zinc-400">
    <p>Your agent sends prompts and context to your selected AI provider and the models it uses, under their own terms. You are responsible for your credentials, provider charges and what the agent does; review its changes before relying on them. See our <a href="https://workspaced.online/terms" target="_blank" rel="noopener noreferrer" className={link}>Terms of service</a> and <a href="https://workspaced.online/privacy" target="_blank" rel="noopener noreferrer" className={link}>Privacy policy</a>.</p>
    <label className="flex cursor-pointer items-start gap-3 font-medium text-zinc-800 dark:text-zinc-200"><input type="checkbox" checked={accepted} disabled={disabled} onChange={event => onChange(event.target.checked)} className="mt-1 size-4 shrink-0 accent-zinc-900 dark:accent-white" /><span>I acknowledge the third-party AI risks and my responsibilities described in the Terms.</span></label>
  </div>;
}

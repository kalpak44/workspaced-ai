import { TriangleAlert } from "lucide-react";

/** Short acknowledgement of Terms sections 5, 6 and 9 and the Privacy policy's AI-provider disclosure. */
export function AiProviderNotice({ accepted, onChange, disabled = false }: { readonly accepted: boolean; readonly onChange: (accepted: boolean) => void; readonly disabled?: boolean }) {
  return <div className="space-y-3 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-6 dark:border-amber-900 dark:bg-amber-950/20">
    <p className="flex items-center gap-2 font-semibold text-amber-900 dark:text-amber-200"><TriangleAlert aria-hidden className="size-4 shrink-0" />Before connecting an AI provider</p>
    <p className="text-zinc-700 dark:text-zinc-300">Your workspace sends prompts and context to OpenRouter and the AI providers it routes to, under their own terms and privacy policies. You are responsible for your data, credentials, provider charges and actions taken by your agent. AI can give incorrect or unsafe answers and make harmful changes; review and test its output before relying on it.</p>
    <p className="text-zinc-700 dark:text-zinc-300">As described in our Terms, Workspaced does not control third-party providers and is not responsible for their availability, pricing, security or AI output, subject to the limitations and applicable law in those Terms.</p>
    <p className="text-zinc-600 dark:text-zinc-400">Read our <a href="https://workspaced.online/terms" target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">Terms of service</a> and <a href="https://workspaced.online/privacy" target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">Privacy policy</a>.</p>
    <label className="flex cursor-pointer items-start gap-3 border-t border-amber-200 pt-3 dark:border-amber-900"><input type="checkbox" checked={accepted} disabled={disabled} onChange={event => onChange(event.target.checked)} className="mt-1 size-4 shrink-0 accent-amber-700" /><span>I acknowledge the third-party AI risks and my responsibilities described above.</span></label>
  </div>;
}

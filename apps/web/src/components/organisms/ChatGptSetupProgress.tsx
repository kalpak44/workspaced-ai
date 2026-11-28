import { ArrowRight, Check, Circle } from "lucide-react";
import type { WorkspaceStatus } from "../../hooks/useWorkspaces";
import { Spinner } from "../atoms";

export function ChatGptSetupProgress({ workspaceId, status, connected }: { readonly workspaceId: string; readonly status: WorkspaceStatus; readonly connected: boolean }) {
  const ready = status === "READY";
  const starting = status === "INITIALIZING";
  const message = status === "FAILED" ? "The workspace could not start. Use workspace actions to retry, then return here."
    : status === "PAUSED" ? "Start the workspace from workspace actions to continue setup."
    : !ready ? "Your workspace is starting. This page updates automatically; ChatGPT sign-in becomes available when it is ready."
    : connected ? "ChatGPT is connected. Your workspace is ready for its first message."
    : "Your workspace is ready. Acknowledge the notice below, then select Connect ChatGPT and complete sign-in.";
  const current = !ready ? 0 : connected ? 2 : 1;
  const steps = ["Prepare workspace", "Connect ChatGPT", "Start chatting"];
  return <section aria-label="ChatGPT workspace setup" className="rounded-xl border border-zinc-200 bg-zinc-50 p-4 dark:border-zinc-800 dark:bg-zinc-900/50 sm:p-5">
    <div aria-live="polite" className="space-y-1.5">
      <p className="text-xs font-medium uppercase tracking-wide text-zinc-500">{connected && ready ? "Setup complete" : `Step ${ready ? 5 : 4} of 5 · Workspace creation`}</p>
      <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">{connected && ready ? "You’re ready to chat" : "Finish creating your workspace"}</h3>
      <p className="text-sm leading-6 text-zinc-600 dark:text-zinc-400">{message}</p>
    </div>
    <ol className="mt-4 grid gap-3 sm:grid-cols-3">
      {steps.map((step, index) => <li key={step} aria-current={index === current ? "step" : undefined} className={`flex items-center gap-2 text-xs font-medium ${index <= current ? "text-zinc-900 dark:text-zinc-100" : "text-zinc-400 dark:text-zinc-500"}`}>
        <span className={`flex size-6 shrink-0 items-center justify-center rounded-full ${index < current ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900" : "border border-current"}`}>
          {index < current ? <Check aria-hidden className="size-3.5" /> : starting && index === 0 ? <Spinner label="Preparing workspace" className="size-3.5" /> : index === 2 ? <ArrowRight aria-hidden className="size-3.5" /> : index === current ? index + 4 : <Circle aria-hidden className="size-2" />}
        </span>{step}
      </li>)}
    </ol>
    {ready && connected && <a href={`/workspaces/${workspaceId}`} className="mt-4 inline-flex items-center gap-1.5 text-sm font-semibold text-zinc-900 underline underline-offset-4 dark:text-zinc-100">Open workspace <ArrowRight aria-hidden className="size-4" /></a>}
    {!connected && <p className="mt-4 text-xs text-zinc-500 dark:text-zinc-400">VM costs are separate from your ChatGPT plan. Chat stays unavailable until sign-in is complete.</p>}
  </section>;
}

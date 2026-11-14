/** A small label, such as a recipe's tag or its visibility. */
export function Tag({ label, tone = "plain" }: { readonly label: string; readonly tone?: "plain" | "strong" }) {
  const colours =
    tone === "strong"
      ? "bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900"
      : "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300";
  return <span className={`inline-flex max-w-full items-center truncate rounded px-1.5 py-0.5 text-xs font-medium ${colours}`}>{label}</span>;
}

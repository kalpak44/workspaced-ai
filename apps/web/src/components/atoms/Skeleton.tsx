/** A pulsing block in the shape of content that is still loading; hidden from readers. */
export function Skeleton({ className = "h-3 w-full" }: { readonly className?: string }) {
  return <span aria-hidden className={`block animate-pulse rounded bg-zinc-200/80 dark:bg-zinc-800 ${className}`} />;
}

import { Star } from "lucide-react";

interface Props {
  /** From 0 to 5; a fraction fills that share of the next star. */
  readonly value: number;
  readonly className?: string;
}

/** A read-only five-star rating, announced as its number. */
export function Stars({ value, className = "size-3.5" }: Props) {
  return (
    <span role="img" aria-label={`Rated ${value.toFixed(1)} out of 5`} className="inline-flex items-center gap-0.5">
      {[0, 1, 2, 3, 4].map((index) => {
        const fill = Math.max(0, Math.min(1, value - index));
        return (
          <span key={index} aria-hidden className="relative inline-flex">
            <Star className={`${className} text-zinc-300 dark:text-zinc-700`} />
            {fill > 0 && (
              <span className="absolute inset-0 overflow-hidden" style={{ width: `${fill * 100}%` }}>
                <Star className={`${className} fill-amber-400 text-amber-400`} />
              </span>
            )}
          </span>
        );
      })}
    </span>
  );
}

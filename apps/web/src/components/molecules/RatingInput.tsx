import { Star } from "lucide-react";
import { useState } from "react";

interface Props {
  /** The user's own stars, or null while they have not rated. */
  readonly value: number | null;
  readonly onChange: (stars: number | null) => void;
  readonly disabled?: boolean;
}

/** Five stars to rate with; choosing the current rating again takes it back. */
export function RatingInput({ value, onChange, disabled = false }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const shown = hover ?? value ?? 0;
  return (
    <div role="radiogroup" aria-label="Your rating" className="flex items-center" onMouseLeave={() => setHover(null)}>
      {[1, 2, 3, 4, 5].map((stars) => (
        <button
          key={stars}
          type="button"
          role="radio"
          aria-checked={value === stars}
          aria-label={stars === 1 ? "1 star" : `${stars} stars`}
          disabled={disabled}
          onMouseEnter={() => setHover(stars)}
          onClick={() => onChange(value === stars ? null : stars)}
          className="rounded p-0.5 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Star aria-hidden className={`size-5 transition-colors ${stars <= shown ? "fill-amber-400 text-amber-400" : "text-zinc-300 dark:text-zinc-600"}`} />
        </button>
      ))}
    </div>
  );
}

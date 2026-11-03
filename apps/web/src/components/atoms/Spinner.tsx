import { LoaderCircle } from "lucide-react";

interface Props {
  readonly className?: string;
  /** Announced as a status when given; decorative otherwise. */
  readonly label?: string;
}

export function Spinner({ className = "size-4", label }: Props) {
  return label ? (
    <LoaderCircle role="status" aria-label={label} className={`animate-spin ${className}`} />
  ) : (
    <LoaderCircle aria-hidden className={`animate-spin ${className}`} />
  );
}
